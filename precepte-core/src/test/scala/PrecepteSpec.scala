package com.mfglabs
package precepte

import org.scalatest._
import Matchers._
import Inspectors._

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Millis, Seconds, Span => TSpan}

import scala.language.higherKinds

class PrecepteSpec extends FlatSpec with ScalaFutures {

  implicit val defaultPatience =
    PatienceConfig(timeout =  TSpan(300, Seconds), interval = TSpan(5, Millis))

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Future
  import scalaz.std.scalaFuture._
  import scalaz.syntax.monad._
  import scalaz.EitherT

  val taggingContext = new PCTX0[Future, Unit]
  import taggingContext._
  import Precepte._

  val env = BaseEnv(Tags.Host("localhost"), Tags.Environment.Test, Tags.Version("1.0"))

  private def tags(n: String) = BaseTags(Tags.Callee(n), Tags.Category.Database)

  def p[C, S <: PST0[C], G <: Graph[BaseTags, S, G]](g: G, before: String = ""): Unit = {
    val txt = g match {
      case Root(span, _) =>
        s"Root[$span]"
      case GraphNode(id, _, tags, _) =>
        s"GraphNode[$id]: $tags"
    }

    println(before + txt)

    for (c <- g.children) {
      c match {
        case node@GraphNode(_, _, _, _) =>
          p[C, S, GraphNode[BaseTags, S]](node, before + "  ")
      }
    }
  }

  def toStates[C](g: Root[BaseTags, PST0[C]]): Seq[PST0[C]] = {
    def go(g: GraphNode[BaseTags, PST0[C]], span: Span, path: Call.Path[BaseTags], states: Seq[PST0[C]]): Seq[PST0[C]] = {
      val GraphNode(id, value, tags, cs) = g
      val p = path :+ Call(id, tags)
      val st = PST0[C](span, env, p, value.value)
      val cst = cs.map{ c =>
        go(c, span, p, Seq.empty)
      }.flatten
      (states :+ st) ++ cst
    }

    g.children.map { c =>
      go(c, g.span, Vector.empty, Seq.empty)
    }.flatten
  }


  def nostate = PST0[Unit](Span.gen, env, Vector.empty, ())

  val ids = PIdStream((1 to 30).map(i => PId(i.toString)).toStream)

  import Tags.Callee

  "Precepte" should "run/eval simple" in {
    def f1 = Precepte(tags("simple.f1")){(_: PST0[Unit]) => 1.point[Future]}
    def f2(i: Int) = Precepte(tags("simple.f2")){(_: PST0[Unit]) => s"foo $i".point[Future]}

    val res = for {
      i <- f1
      r <- f2(i)
    } yield r

    val (a, s, ids) = res.run(nostate).futureValue
    a should ===("foo 1")
    s.env should ===(env)
    s.path.size should ===(2)
    s.path(0).tags should ===(tags("simple.f1"))
    s.path(1).tags should ===(tags("simple.f2"))

    val a0 = res.eval(nostate).futureValue
    a0 should ===("foo 1")
  }

  it should "observe simple" in {
    def f1 =
      Precepte(tags("simple.f1")){(_: PST0[Unit]) => 1.point[Future]}
        .flatMap(a => Precepte(tags("simple.f1.1")){(_: PST0[Unit]) => (a+1).point[Future]})
        .flatMap(a => Precepte(tags("simple.f1.2")){(_: PST0[Unit]) => (a+1).point[Future]})
        .flatMap(a => Precepte(tags("simple.f1.3")){(_: PST0[Unit]) => (a+1).point[Future]})
    def f2(i: Int) =
      Precepte(tags("simple.f2")){(_: PST0[Unit]) => s"foo $i".point[Future]}
        .flatMap(a => Precepte(tags("simple.f2.1")){(_: PST0[Unit]) => s"$a.1".point[Future]})
        .flatMap(a => Precepte(tags("simple.f2.2")){(_: PST0[Unit]) => s"$a.2".point[Future]})
    def f3(s: String) =
      Precepte(tags("simple.f3")){(_: PST0[Unit]) => s"$s finito".point[Future]}

    val res = for {
      i <- f1
      s <- f2(i)
      r <- f3(s)
    } yield r

    val (a, s, ids, graph) = res.observe0(nostate).futureValue
    println("-- graph0 --")
    val tree = graph.toTree
    val g = tree.drawTree
    println(g)
    // p[Unit, PST0[Unit], Root[BaseTags, PST0[Unit]]](graph)
    println("----")

    val sf1 = tree.subForest(0)
    val Node0(_, t1) = sf1.rootLabel
    t1 should ===(tags("simple.f1"))
    val Node0(_, t11) = sf1.subForest(0).rootLabel
    t11 should ===(tags("simple.f1.1"))
    val Node0(_, t12) = sf1.subForest(1).rootLabel
    t12 should ===(tags("simple.f1.2"))
    val Node0(_, t13) = sf1.subForest(2).rootLabel
    t13 should ===(tags("simple.f1.3"))

    val sf2 = tree.subForest(1)
    val Node0(_, t2) = sf2.rootLabel
    t2 should ===(tags("simple.f2"))
    val Node0(_, t21) = sf2.subForest(0).rootLabel
    t21 should ===(tags("simple.f2.1"))
    val Node0(_, t22) = sf2.subForest(1).rootLabel
    t22 should ===(tags("simple.f2.2"))

    val sf3 = tree.subForest(2)
    val Node0(_, t3) = sf3.rootLabel
    t3 should ===(tags("simple.f3"))

  }


  it should "optT" in {
    val f1 = Precepte(tags("opt"))((_: PST0[Unit]) => Option("foo").point[Future])
    val f2 = Precepte(tags("opt"))((_: PST0[Unit]) => Option(1).point[Future])
    val f3 = Precepte(tags("opt"))((_: PST0[Unit]) => (None: Option[Int]).point[Future])


    val res = for {
      e1 <- trans(f1)
      e2 <- trans(f2)
    } yield (e1, e2)

    res.run.eval(nostate).futureValue should ===(Some(("foo",1)))

    val res2 = for {
      e1 <- trans(f1)
      e2 <- trans(f3)
    } yield (e1, e2)

    res2.run.eval(nostate).futureValue should ===(None)

    val res3 = for {
      e1 <- trans(f3)
      e2 <- trans(f2)
    } yield (e1, e2)

    res3.run.eval(nostate).futureValue should ===(None)
  }


  it should "listT" in {
    val f1 = Precepte(tags("listT"))((_: PST0[Unit]) => List("foo", "bar").point[Future])
    val f2 = Precepte(tags("listT"))((_: PST0[Unit]) => List(1, 2).point[Future])
    val f3 = Precepte(tags("listT"))((_: PST0[Unit]) => List[Int]().point[Future])

    val res = for {
      e1 <- trans(f1)
      e2 <- trans(f2)
    } yield (e1, e2)

    res.run.eval(nostate).futureValue should ===(List(("foo",1), ("foo",2), ("bar",1), ("bar",2)))

    val res2 = for {
      e1 <- trans(f1)
      e2 <- trans(f3)
    } yield (e1, e2)

    res2.run.eval(nostate).futureValue should ===(List())

    val res3 = for {
      e1 <- trans(f3)
      e2 <- trans(f2)
    } yield (e1, e2)

    res3.run.eval(nostate).futureValue should ===(List())
  }


  it should "EitherT" in {
    import scalaz.{ \/ , \/-, -\/}
    import EitherT.eitherTFunctor

    val f1: Precepte[String \/ String] =
      Precepte(tags("f1"))(_ => \/-("foo").point[Future])
    val f2: Precepte[String \/ Int] =
      Precepte(tags("f2"))(_ => \/-(1).point[Future])
    val f3: Precepte[String \/ String] =
      Precepte(tags("f3"))(_ => -\/("Error").point[Future])

    type Foo[A] = EitherT[Future, String, A]

    val res = for {
      e1 <- trans(f1)
      e2 <- trans(f2)
    } yield (e1, e2)

    res.run.eval(nostate).futureValue should ===(\/-("foo" -> 1))

    val error = -\/("Error")
    val res2 = for {
      e1 <- trans(f1)
      e2 <- trans(f3)
    } yield (e1, e2)

    res2.run.eval(nostate).futureValue should ===(error)

    val res3 = for {
      e1 <- trans(f3)
      e2 <- trans(f2)
    } yield (e1, e2)

    val (rr0, _, _, graph0) = res3.run.observe0(nostate).futureValue
    rr0 should ===(error)
    println("-- graph0 --")
    val tree = graph0.toTree
    println(tree.drawTree)
    println("----")

    val sf = tree.subForest(0)
    sf.subForest should be(empty)
    val Node0(_, t) = sf.rootLabel
    t should ===(tags("f3"))

    // val (rr1, _, _, graph1) = res3.run.observe(nostate).futureValue
    // rr1 should ===(error)
    // println("-- graph1 --")
    // p[Unit, PST0[Unit], Root[BaseTags, PST0[Unit]]](graph1)
    // println("----")

  }



  it should "trivial" in {

    def f1 = Precepte(tags("trivial.f1")){ (_: PST0[Unit]) => 1.point[Future] }
    def f2(i: Int) = Precepte(tags("trivial.f2")){ (_: PST0[Unit]) => s"foo $i".point[Future] }
    def f3(i: Int) = Precepte(tags("trivial.f3")){ (_: PST0[Unit]) => (i + 1).point[Future] }

    val (result0, _, _, graph0) = f1.observe(nostate, ids).futureValue
    result0 should ===(1)
    graph0.children.size should ===(1)
    graph0.children(0).id should ===(PId("1"))

    println("-- graph0 --")
    p[Unit, PST0[Unit], Root[BaseTags, PST0[Unit]]](graph0)
    println("----")

    val (_, _, _, graphm) = Precepte(tags("graphm0"))(Precepte(tags("graphm"))(f1)).observe(nostate).futureValue
    println("-- graphm --")
    p[Unit, PST0[Unit], Root[BaseTags, PST0[Unit]]](graphm)
    println("----")

    val res =
      for {
        i <- f1
        r <- f2(i)
      } yield r


    val (result11, _, _, graph11) = res.observe0(nostate, ids).futureValue
    println("-- graph11 --")
    val tree11 = graph11.toTree
    println(tree11.drawTree)
    println("----")

    result11 should ===("foo 1")

    val (result12, _, _, graph12) = res.observe(nostate, ids).futureValue
    graph12.children.size should ===(2)
    graph12.children(0).id should ===(PId("1"))
    graph12.children(1).id should ===(PId("2"))

    result12 should ===("foo 1")

    println("-- graph12 --")
    p[Unit, PST0[Unit], Root[BaseTags, PST0[Unit]]](graph12)
    println("----")

    // val (result1, _, _, graph1) = Precepte(tags("trivial.anon"))(res).observe(nostate).futureValue

    // println("-- graph1 --")
    // p[Unit, PST0[Unit], Root[BaseTags, PST0[Unit]]](graph1)
    // println("----")

    // val res2 =
    //   for {
    //     i <- Precepte(tags("trivial.anon2"))(f1)
    //     r <- f2(i)
    //   } yield r

    // val (result2, _, _, graph2) = res2.observe(nostate, ids).futureValue
    // println("-- graph2 --")
    // p[Unit, PST0[Unit], Root[BaseTags, PST0[Unit]]](graph2)
    // println("----")
  }

/*
  it should "pass context" in {
    val ctxs = scala.collection.mutable.ArrayBuffer[PST0[Unit]]()

    def push(state: PST0[Unit]): Unit = {
      ctxs += state
      ()
    }

    def f1 = Precepte(tags("f1")){ (c: PST0[Unit]) =>
      push(c)
      1.point[Future]
    }

    val (res, _, _, graph) = f1.runGraph(nostate).futureValue
    res should ===(1)
    ctxs.length should ===(1)

    ctxs.toList should ===(toStates(graph).toList)
  }

  it should "preserve context on map" in {
    val ctxs = scala.collection.mutable.ArrayBuffer[PST0[Unit]]()

    def push(state: PST0[Unit]): Unit = {
      ctxs += state
      ()
    }

    def f1 = Precepte(tags("f1")){ (c: PST0[Unit]) =>
      push(c)
      1.point[Future]
    }.map(identity).map(identity).map(identity).map(identity)

    val (res, _, _, graph) = f1.runGraph(nostate).futureValue
    res should ===(1)

    ctxs.length should ===(1)
    ctxs.head.path.length should ===(1)

    ctxs.toList should ===(toStates(graph).toList)
  }

  it should "preserve context on flatMap" in {
    val ctxs = scala.collection.mutable.ArrayBuffer[PST0[Unit]]()

    def push(state: PST0[Unit]): Unit = {
      ctxs += state
      ()
    }

    def f1 = Precepte(tags("f1")){ (c: PST0[Unit]) =>
      push(c)
      1.point[Future]
    }

    def f2(i: Int) = Precepte(tags("f2")){ (c: PST0[Unit]) =>
      push(c)
      s"foo $i".point[Future]
    }

    def f3(s: String) = Precepte(tags("f3")){ (c: PST0[Unit]) =>
      push(c)
      s"f3 $s".point[Future]
    }

    val f = Precepte(tags("anon0"))(f1
      .flatMap(i => f2(i))
      .flatMap(s => f3(s)))

    val (res, _, _, graph) = f.runGraph(nostate).futureValue
    res should ===("f3 foo 1")

    ctxs.length should ===(3)
    ctxs.toList should ===(toStates(graph).toList.drop(1))
  }

  it should "stack contexts" in {
    def f1 = Precepte(tags("f1")){ (c: PST0[Unit]) =>
      1.point[Future]
    }

    val stacked = Precepte(tags("stacked"))(f1)
    val (r, _, _, graph) = stacked.runGraph(nostate).futureValue
    r should ===(1)

    graph.children should have length 1
    graph.children.head.children should have length 1
  }

  it should "provide context to C" in {
    val ctxs = scala.collection.mutable.ArrayBuffer[PST0[Unit]]()

    def push(state: PST0[Unit]): Unit = {
      ctxs += state
      ()
    }

    def f1 = Precepte(tags("f1")) { (c: PST0[Unit]) =>
      push(c)
      1.point[Future]
    }

    def f2(i: Int) = Precepte(tags("f2")){ (c: PST0[Unit]) =>
      push(c)
      s"foo $i".point[Future]
    }

    val (graph, res) = f1.run(nostate).futureValue
    res should ===(1)
    ctxs.length should ===(1)
    ctxs.head.path.length should ===(1)


    ctxs.clear()

    val res2 = f1.map(identity)
    res2.eval(nostate)

    ctxs should have length(1)
    forAll(ctxs.map(_.path.length == 1)){_  should ===(true) }


    ctxs.clear()

    val r = for {
      i <- f1
      r <- f2(i)
    } yield r

    r.eval(nostate).futureValue should ===("foo 1")

    ctxs should have length(2)
    ctxs.map(_.span).toSet.size should ===(1) // span is unique
    println("===> CTX:"+ctxs)
    forAll(ctxs.map(_.path.length == 1)){ _ should ===(true) }

    ctxs.clear()

    val res3 = Precepte(tags("res3"))(f1)
    res3.eval(nostate).futureValue should ===(1)

    ctxs should have length(1)
    ctxs.map(_.span).toSet.size should ===(1) // span is unique
    forAll(ctxs.map(_.path.length == 2)){ _ should ===(true) }

    ctxs.clear()

    val res4 = Precepte(tags("res4")) {
      for {
        i <- f1
        r <- f2(i)
      } yield r
    }

    res4.eval(nostate).futureValue should ===("foo 1")

    ctxs should have length(2)
    ctxs.map(_.span).toSet.size should ===(1) // span is unique
    forAll(ctxs.map(_.path.length == 2)){ _ should ===(true) }
  }

  it should "not stack context on trans" in {
    val ctxs = scala.collection.mutable.ArrayBuffer[PST0[Unit]]()

    def push(state: PST0[Unit]): Unit = {
      ctxs += state
      ()
    }

    def f1 = Precepte(tags("f1")) { (c: PST0[Unit]) =>
      push(c)
      Option(1).point[Future]
    }

    def f2(i: Int) = Precepte(tags("f1")){ (c: PST0[Unit]) =>
      push(c)
      Option(s"foo $i").point[Future]
    }

    val res4 = Precepte(tags("res4")) {
      (for {
        i <- trans(f1)
        r <- trans(f2(i))
      } yield r).run
    }

    res4.eval(nostate).futureValue should ===(Some("foo 1"))

    ctxs should have length(2)
    ctxs.map(_.span).toSet.size should ===(1) // span is unique
    forAll(ctxs.map(_.path.length == 2)){ _ should ===(true) }

  }

  it should "real world wb.fr home" in {

    type ST = (Span, Call.Path[BaseTags]) => Log

    val taggingContext = new TaggingContext[BaseTags, PST0[ST], Future]
    import taggingContext._
    import Precepte._
    import scalaz.std.option._

    trait Log {
      def debug(s: String): Unit
    }

    def Logged[A](tags: BaseTags)(f: Log => Future[A]): Precepte[A] =
      Precepte(tags) { (state: PST0[ST]) =>
        f(state.value(state.span, state.path))
      }

    case class Board(pin: Option[Int])
    object BoardComp {
      def get() = Logged(tags("BoardComp.get")) { (logger: Log) =>
        logger.debug("BoardComp.get")
        Board(Option(1)).point[Future]
      }
    }

    case class Community(name: String)
    case class Card(name: String)

    object CardComp {
      def getPin(id: Int) = Logged(tags("BoardComp.getPin")) { (logger: Log) =>
        logger.debug("CardComp.getPin")
        Option(1 -> Card("card 1")).point[Future]
      }

      def countAll() = Logged(tags("CardComp.countAll")) { (logger: Log) =>
        logger.debug("CardComp.countAll")
        Set("Edito", "Video").point[Future]
      }

      def rank() = Logged(tags("CardComp.rank")) { (logger: Log) =>
        logger.debug("CardComp.rank")
        List(1 -> Card("foo"), 1 -> Card("bar")).point[Future]
      }

      def cardsInfos(cs: List[(Int, Card)], pin: Option[Int]) = Logged(tags("CardComp.cardsInfos")) { (logger: Log) =>
        logger.debug("CardComp.cardsInfos")
        List(
          Card("foo") -> List(Community("community 1"), Community("community 2")),
          Card("bar") -> List(Community("community 2"))).point[Future]
      }
    }

    import java.net.URL
    case class Highlight(title: String, cover: URL)
    object HighlightComp {
      def get() = Logged(tags("HighlightComp.get")) { (logger: Log) =>
        logger.debug("HighlightComp.get")
        Highlight("demo", new URL("http://nd04.jxs.cz/641/090/34f0421346_74727174_o2.png")).point[Future]
      }
    }

    val logs = scala.collection.mutable.ArrayBuffer[String]()

    case class Logger(span: Span, path: Call.Path[BaseTags]) extends Log {
      def debug(s: String): Unit = {
        logs += s"[DEBUG] ${span.value} -> /${path.mkString("/")} $s"
        ()
      }
    }

    val getPin =
      (for {
        b   <- trans(BoardComp.get().lift[Option])
        id  <- trans(Precepte(tags("point"))((_: PST0[ST]) => b.pin.point[Future]))
        pin <- trans(CardComp.getPin(id))
      } yield pin).run


    val res = for {
      pin            <- getPin
      cs             <- CardComp.rank()
      cards          <- CardComp.cardsInfos(cs, pin.map(_._1))
      availableTypes <- CardComp.countAll()
      h              <- HighlightComp.get()
    } yield (pin, cs, cards, availableTypes, h)

    def logger(span: Span, path: Call.Path[BaseTags]): Log =
      Logger(span, path)

    val initialState = PST0[ST](Span.gen, env, Vector.empty, logger _)
    res.eval(initialState).futureValue should ===(
      (Some((1, Card("card 1"))),
        List((1, Card("foo")), (1, Card("bar"))),
        List(
          (Card("foo"), List(
            Community("community 1"),
            Community("community 2"))),
          (Card("bar"),List(
            Community("community 2")))),
        Set("Edito", "Video"),
        Highlight("demo", new URL("http://nd04.jxs.cz/641/090/34f0421346_74727174_o2.png")))
    )

    for(l <- logs)
    println(l)
  }

  it should "implement flatMapK" in {

    def f1: Precepte[Int] =
      Precepte(tags("f1")) { (c: PST0[Unit]) =>
        1.point[Future]
      }

    def f2: Precepte[Int] =
      Precepte(tags("f2")){ (c: PST0[Unit]) =>
        Future { throw new RuntimeException("ooopps f2") }
      }

    def f3(i: Int): Precepte[String] =
      Precepte(tags("f3")){ (c: PST0[Unit]) =>
        "foo".point[Future]
      }

    def f4(i: Int): Precepte[String] =
      Precepte(tags("f4")){ (c: PST0[Unit]) =>
        Future { throw new RuntimeException("ooopps f4") }
      }

    (for {
      i <- f2
      // r <- f3(i)
    } yield i)
      .flatMapK{ fut =>
        Precepte(tags("f5")){ (c: PST0[Unit]) => fut.recover { case _ => "recovered" } }
      }
      .eval(nostate).futureValue should ===("recovered")

    (for {
      i <- f1
      r <- f4(i)
    } yield r)
      .flatMapK{ fut =>
        Precepte(tags("f6")){ (c: PST0[Unit]) => fut.recover { case _ => "recovered" } }
      }
      .eval(nostate).futureValue should ===("recovered")

  }

  it should "run flatMapK" in {

    def f1: Precepte[Int] =
      Precepte(tags("f1")) { (c: PST0[Unit]) =>
        1.point[Future]
      }

    val (g, a) = f1.flatMapK(futI => Precepte(tags("f")){ _ => futI.map(i => (i+1)) }).run(nostate).futureValue
    a should equal (2)
  }

  it should "not break type inference" in {
    import scalaz.syntax.monadPlus._
    import scalaz.OptionT._
    val f1 = Option(1).point[Precepte]
    optionT(f1).withFilter(_ => true).withFilter(_ => true).run.eval(nostate).futureValue should ===(Some(1))
  }

*/


  it should "eval flatMapK failure" in {

    def f1: Precepte[Int] =
      Precepte(tags("f1")) { (c: PST0[Unit]) =>
        Future { throw new RuntimeException("ooopps f1") }
      }

    val a = f1
      .flatMapK(futI => Precepte(tags("f")){ _ => Future("recover") })
      .flatMap { a => println(s"A:$a"); 1.point[Precepte] }
      .eval(nostate).futureValue
    a should equal (1)
  }



  it should "not stack overflow" in {
    def pre(l: List[Int], i: Int) = Precepte(tags(s"stack_$i"))((_: PST0[Unit]) => l.point[Future])

    val l = List.iterate(0, 100000){ i => i + 1 }

    val pf = l.foldLeft(
      pre(List(), 0)
    ){ case (p, i) =>
      p.flatMap(l => pre(i +: l, i))
    }

    pf.eval(nostate).futureValue should equal (l.reverse)
  }


}
