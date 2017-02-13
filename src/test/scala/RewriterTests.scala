/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.nio.file.{Path, Paths}

import org.scalatest.{FunSuite, Matchers}
import viper.silver._
import viper.silver.ast._
import viper.silver.ast.utility.Rewriter._
import viper.silver.ast.utility._
import viper.silver.frontend.{SilFrontend, TranslatorState}
import viper.silver.verifier.AbstractError

import scala.collection.mutable
import scala.language.implicitConversions


class RewriterTests extends FunSuite with Matchers {

  test("TestingStuff") {
  }

  test("QuantifiedPermissions") {
    val filePrefix = "transformations\\QuantifiedPermissions\\"
    val files = Seq("simple", "allCases")

    val strat = ViperStrategy.Ancestor({
      case (f@Forall(decl, _, Implies(l, r)), _) if r.isPure =>
        f
      case (f@Forall(decls, triggers, i@Implies(li, And(l, r))), ass) =>
        val forall = Forall(decls, triggers, Implies(li, r)(i.pos, i.info))(f.pos, f.info)
        And(Forall(decls, triggers, Implies(li, l)(i.pos, i.info))(f.pos, f.info), ass.noRec[Forall](forall))(f.pos, f.info)
      case (f@Forall(decls, triggers, i@Implies(li, Implies(l, r))), _) if l.isPure =>
        Forall(decls, triggers, Implies(And(li, l)(i.pos, i.info), r)(i.pos, i.info))(f.pos, f.info)
    })

    val frontend = new DummyFrontend
    files foreach { name => executeTest(filePrefix, name, strat, frontend) }

  }

  test("DisjunctionToInhaleExhaleTests") {
    val filePrefix = "transformations\\DisjunctionToInhaleExhale\\"
    val files = Seq("simple"/*, "nested", "functions"*/)

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
      val fileRef = getClass.getResource(filePrefix + fileName + "Ref.sil")
      assert(fileRes != null, s"File $filePrefix$fileName not found")
      assert(fileRef != null, s"File $filePrefix$fileName Ref not found")
      val file = Paths.get(fileRes.toURI)
      val ref = Paths.get(fileRef.toURI)


      var targetNode: Program = null
      var targetRef: Program = null

      frontend.translate(file) match {
        case (Some(p), _) => targetNode = p
        case (None, errors) => println("Problem with program: " + errors)
      }

      // We use for NonDet a function that has name NonDet(i) where i is number of arguments
      def NonDet(vars: Seq[LocalVarDecl]): Exp = {
        if (vars.isEmpty) return TrueLit()()
        val func: Function = targetNode.functions.find(_.name == "NonDet" + vars.size).get
        FuncApp(func, vars.map { x => x.localVar })()
      }

      // Regular expression
      val t = TreeRegexBuilder.context[Node, LocalVarDecl]
      val strat = t @> (c[QuantifiedExp]( _.variables).** >> r[Or]) |-> { case (o:Or, c) => InhaleExhaleExp(CondExp(NonDet(c), o.left, o.right)(), o)()}


      /*val strat = ViperStrategy.Context[Seq[LocalVarDecl]]({
        case (o@Or(l, r), c) =>
          InhaleExhaleExp(CondExp(NonDet(c.c), l, r)(), c.noRec[Or](o))()
      }, Seq.empty, {
        case (q: QuantifiedExp, c) => c ++ q.variables
      })*/

      frontend.translate(ref) match {
        case (Some(p), _) => targetRef = p
        case (None, errors) => println("Problem with program: " + errors)
      }

      val res = strat.execute[Program](targetNode)
      //  println("Old: " + targetNode.toString())
      println("New: " + res.toString())
      println("Reference: " + targetRef.toString())
      assert(res.toString() == targetRef.toString(), "Files are not equal")
    }
    }
  }

  test("OLDDisjunctionToInhaleExhaleTests") {
    // Is working correctly but rewrites right hand sides of IE expression too. Therefore does not match with more complex testcases
    // 17 vs 7 line numbers
    val filePrefix = "transformations\\DisjunctionToInhaleExhale\\"
    val files = Seq("simple", "nested"/*, "functions"*/)

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
      val fileRef = getClass.getResource(filePrefix + fileName + "Ref.sil")
      assert(fileRes != null, s"File $filePrefix$fileName not found")
      assert(fileRef != null, s"File $filePrefix$fileName Ref not found")
      val file = Paths.get(fileRes.toURI)
      val ref = Paths.get(fileRef.toURI)


      var targetNode: Program = null
      var targetRef: Program = null

      frontend.translate(file) match {
        case (Some(p), _) => targetNode = p
        case (None, errors) => println("Problem with program: " + errors)
      }

      // We use for NonDet a function that has name NonDet(i) where i is number of arguments
      def NonDet(vars: Seq[LocalVar]): Exp = {
        if (vars.isEmpty) return TrueLit()()
        val func: Function = targetNode.functions.find(_.name == "NonDet" + vars.size).get
        FuncApp(func, vars)()
      }

      frontend.translate(ref) match {
        case (Some(p), _) => targetRef = p
        case (None, errors) => println("Problem with program: " + errors)
      }

      var quantifiedVariables = Seq.empty[ast.LocalVar]
      val res = targetNode.transform {
        case q: ast.QuantifiedExp =>
          quantifiedVariables = quantifiedVariables ++ q.variables.map(_.localVar)
          q
      }(recursive = {
        _ => true
      }, post = {
        case q: ast.QuantifiedExp =>
          val qvs = q.variables.map(_.localVar)
          quantifiedVariables = quantifiedVariables filterNot qvs.contains
          q
        case e @ ast.Or(e0, e1) =>
          val cond = NonDet(quantifiedVariables)
          val condExp = ast.CondExp(cond, e0, e1)(e.pos, e.info)
          ast.InhaleExhaleExp(condExp, e)(e.pos, e.info)
      })



      //  println("Old: " + targetNode.toString())
      println("New: " + res.toString())
      println("Reference: " + targetRef.toString())
      assert(res.toString() == targetRef.toString(), "Files are not equal")
    }
    }
  }

  test("ImplicationToDisjunctionTests") {
    val filePrefix = "transformations\\ImplicationsToDisjunction\\"
    val files = Seq("simple", "nested", "traverseEverything")


    // Regular expression
    val t = TreeRegexBuilder.simple[Node]
    val strat = t @> r[Implies] |-> { case (i:Implies, c) => Or(Not(i.left)(), i.right)()}

    // Create new strategy. Parameter is the partial function that is applied on all nodes
    //val strat = ViperStrategy.Slim({
    //  case Implies(left, right) => Or(Not(left)(), right)()
    //})

    val frontend = new DummyFrontend
    files foreach { name => executeTest(filePrefix, name, strat, frontend) }
  }


  test("WhileToIfAndGoto") {
    val filePrefix = "transformations\\WhileToIfAndGoto\\"
    val files = Seq("simple", "nested")

    // Example of how to transform a while loop into if and goto
    // Keeping metadata is awful when creating multiple statements from a single one and we need to think about this case, but at least it is possible
    var count = 0
    val strat = ViperStrategy.Slim({
      case w: While =>
        val invars: Exp = w.invs.reduce((x: Exp, y: Exp) => And(x, y)())
        count = count + 1
        Seqn(Seq(
          Assert(invars)(w.invs.head.pos, w.invs.head.info),
          If(Not(w.cond)(w.cond.pos, w.cond.info), Goto("skiploop" + count)(w.pos, w.info), Seqn(Seq())(w.pos, w.info))(w.pos, w.info),
          Label("loop" + count, Seq(TrueLit()()))(w.pos, w.info),
          w.body,
          Assert(invars)(w.invs.head.pos, w.invs.head.info),
          If(w.cond, Goto("loop" + count)(w.pos, w.info), Seqn(Seq())(w.pos, w.info))(w.pos, w.info),
          Label("skiploop" + count, Seq(TrueLit()()))(w.pos, w.info)
        ))(w.pos, w.info)
    })

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      count = 0
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }
  }

  test("ManyToOneAssert") {
    val filePrefix = "transformations\\ManyToOneAssert\\"
    val files = Seq("simple", "interrupted", "nested", "nestedBlocks")

    val strat = ViperStrategy.Ancestor({
      case (a: Assert, c) =>
        c.previous match {
          case Some(Assert(_)) => Seqn(Seq())() // If previous node is assertion we go to noop
          case _ =>
            // Otherwise we take all following assertions and merge their expressions into one
            c.successors.takeWhile({
              // Take all following assertions
              case Assert(_) => true
              case _ => false
            }).collect({ case i: Assert => i }) match {
              // Collect works as a cast to list of assertion since take while does not do this
              case Seq() => a
              case as =>
                // Merge in case of multiple assertions
                val foldedExpr = as collect { case assertion => assertion.exp } reduceRight { (l, r) => And(l, r)() }
                Assert(And(a.exp, foldedExpr)())(a.pos, a.info)
            }
        }
    })

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }
  }

  test("ManyToOneAssert2") {
    val filePrefix = "transformations\\ManyToOneAssert\\"
    val files = Seq("simple", "interrupted", "nested", "nestedBlocks")
    var accumulator: mutable.ListBuffer[Exp] = mutable.ListBuffer.empty[Exp]

    val strat = ViperStrategy.Ancestor({
      case (a: Assert, c) =>
        accumulator += a.exp
        c.next match {
          case Some(Assert(_)) =>
            Seqn(Seq())()
          case _ =>
            val result = Assert(accumulator.reduceRight(And(_, _)()))()
            accumulator.clear()
            result
        }
    })

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }
  }

  test("OLDManyToOneAssert2") {
    // 16 lines compared to 12 lines
    // Need to find successor ourselves

    val filePrefix = "transformations\\ManyToOneAssert\\"
    val files = Seq("simple", "interrupted", "nested", "nestedBlocks")
    var accumulator: mutable.ListBuffer[Exp] = mutable.ListBuffer.empty[Exp]

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
      assert(fileRes != null, s"File $filePrefix$fileName not found")
      val file = Paths.get(fileRes.toURI)
      var targetNode: Program = null
      var targetRef: Program = null

      frontend.translate(file) match {
        case (Some(p), _) => targetNode = p
        case (None, errors) => println("Problem with program: " + errors)
      }

      // TODO: Transformer here
      var enclSeq: Seqn = null
      val res = targetNode.transform({
        case a: Assert =>
          accumulator += a.exp

          val family = enclSeq.getChildren.asInstanceOf[Seq[Node]]
          val next = family.dropWhile(_ ne a).drop(1).headOption

          next match {
            case Some(Assert(_)) =>
              Seqn(Seq())()
            case _ =>
              val result = Assert(accumulator.reduceRight(And(_, _)()))()
              accumulator.clear()
              result
          }
        case (s: Seqn) => enclSeq = s; s
      })(_ => true)


      val fileRef = getClass.getResource(filePrefix + fileName + "Ref.sil")
      assert(fileRef != null, s"File $filePrefix$fileName Ref not found")

      val ref = Paths.get(fileRef.toURI)
      frontend.translate(ref) match {
        case (Some(p), _) => targetRef = p
        case (None, errors) => println("Problem with program: " + errors)
      }

      //  println("Old: " + targetNode.toString())
      println("New: " + res.toString)
      println("Reference: " + targetRef.toString())
      assert(res.toString == targetRef.toString(), "Files are not equal")
    }
    }
  }

  test("FoldConstants") {
    val filePrefix = "transformations\\FoldConstants\\"
    val files = Seq("simple", "complex")


    val strat = ViperStrategy.Slim({
      case root@Add(i1: IntLit, i2: IntLit) => IntLit(i1.i + i2.i)(root.pos, root.info)
      case root@Sub(i1: IntLit, i2: IntLit) => IntLit(i1.i - i2.i)(root.pos, root.info)
      case root@Div(i1: IntLit, i2: IntLit) => if (i2.i != 0) IntLit(i1.i / i2.i)(root.pos, root.info) else root
      case root@Mul(i1: IntLit, i2: IntLit) => IntLit(i1.i * i2.i)(root.pos, root.info)
    }) traverse Traverse.BottomUp

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }

  }

  test("UnfoldedChildren") {
    val filePrefix = "transformations\\UnfoldedChildren\\"
    val files = Seq("fourAnd")

    //val t = new TreeRegexBuilder[Node, Node]()
    //nP[FuncApp](f => f.funcname == "fourAnd" && f.args.contains(FalseLit()())) > r[Exp] // |-> { case (e:Exp, _) => FalseLit()(e.pos, e.info, e.errT) }

    val strat: StrategyInterface[Node] = ViperStrategy.Ancestor({
      case (e: Exp, c) => c.parent match {
        case f: FuncApp => if (f.funcname == "fourAnd" && c.siblings.contains(FalseLit()())) {
          FalseLit()(e.pos, e.info)
        } else {
          e
        }
        case _ => e
      }
    }) traverse Traverse.BottomUp

    val frontend = new DummyFrontend
    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }
  }

  test("CountAdditions") {
    val filePrefix = "transformations\\CountAdditions\\"
    val filesAndResults = Seq(("simple", 3), ("nested", 10), ("traverseEverything", 12))

    val query = new Query[Node, Int]({
      case a: Add => 1
    }) neutralElement 0 accumulate { (s: Seq[Int]) => s.sum }

    val frontend = new DummyFrontend

    filesAndResults foreach ((tuple) => {
      val fileName = tuple._1
      val result = tuple._2

      val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
      assert(fileRes != null, s"File $filePrefix$fileName not found")
      val file = Paths.get(fileRes.toURI)

      var targetNode: Node = null
      frontend.translate(file) match {
        case (Some(p), _) => targetNode = p
        case (None, errors) => println("Problem with program: " + errors)
      }
      val res = query.execute(targetNode)

      //      println("Actual: " + res)
      //      println("Expected: " + result)
      assert(res == result, "Results are not equal")
    })
  }

  test("OLDMethodCallDesugaring") {
    // Basically the exact same program
    // No features from strategy used only partial function and go!

    val filePrefix = "transformations\\MethodCallDesugaring\\"
    val files = Seq("simple", "withArgs", "withArgsNRes", "withFields")

    val frontend = new DummyFrontend

    files foreach { fileName: String => {
      val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
      assert(fileRes != null, s"File $filePrefix$fileName not found")
      val file = Paths.get(fileRes.toURI)
      var targetNode: Program = null
      var targetRef: Program = null

      frontend.translate(file) match {
        case (Some(p), _) => targetNode = p
        case (None, errors) => println("Problem with program: " + errors)
      }

      // TODO: Transformer here
      val res = targetNode.transform({
        case m: MethodCall =>
          // Get method declaration
          val mDecl = targetNode.methods.find(_.name == m.methodName).get

          // Create an exhale statement for every precondition and replace parameters with arguments
          var replacer: Map[LocalVar, Exp] = mDecl.formalArgs.zip(m.args).map(x => x._1.localVar -> x._2).toMap
          val pfunc: PartialFunction[Node, Node] = {
            case l: LocalVar => if (replacer.contains(l)) replacer(l) else l
          }
          val exPres = mDecl.pres.map(_.transform(pfunc)(_ => true)).map(x => Exhale(x)(m.pos, m.info))

          // Create an inhale statement for every postcondition, replace parameters with arguments and replace result parameters with receivers
          val replacedArgs = mDecl.posts.map(_.transform(pfunc)(_ => true))
          replacer = mDecl.formalReturns.zip(m.targets).map(x => x._1.localVar -> x._2).toMap
          val inPosts = replacedArgs.map(_.transform(pfunc)(_ => true)).map(x => Inhale(x)(m.pos, m.info))

          Seqn(exPres ++ inPosts)(m.pos, m.info)
      })()


      val fileRef = getClass.getResource(filePrefix + fileName + "Ref.sil")
      assert(fileRef != null, s"File $filePrefix$fileName Ref not found")

      val ref = Paths.get(fileRef.toURI)
      frontend.translate(ref) match {
        case (Some(p), _) => targetRef = p
        case (None, errors) => println("Problem with program: " + errors)
      }

      //  println("Old: " + targetNode.toString())
      println("New: " + res.toString)
      println("Reference: " + targetRef.toString())
      assert(res.toString == targetRef.toString(), "Files are not equal")
    }
    }
  }

  test("MethodCallDesugaring") {
    // Careful: Don't use old inside postcondition. It is not yet supported. maybe I will update the testcase
    val filePrefix = "transformations\\MethodCallDesugaring\\"
    val files = Seq("simple", "withArgs", "withArgsNRes", "withFields")

    val frontend = new DummyFrontend

    val strat = ViperStrategy.Ancestor({
      case (m: MethodCall, anc) =>
        // Get method declaration
        val mDecl = anc.ancestorList.head.asInstanceOf[Program].methods.find(_.name == m.methodName).get

        // Create an exhale statement for every precondition and replace parameters with arguments
        var replacer: Map[LocalVar, Exp] = mDecl.formalArgs.zip(m.args).map(x => x._1.localVar -> x._2).toMap
        val replaceStrat = StrategyBuilder.SlimStrategy[Node]({
          case l: LocalVar => if (replacer.contains(l)) replacer(l) else l
        })
        val exPres = mDecl.pres.map(replaceStrat.execute[Exp](_)).map(x => Exhale(x)(m.pos, m.info))

        // Create an inhale statement for every postcondition, replace parameters with arguments and replace result parameters with receivers
        val replacedArgs = mDecl.posts.map(replaceStrat.execute[Exp](_))
        replacer = mDecl.formalReturns.zip(m.targets).map(x => x._1.localVar -> x._2).toMap
        val inPosts = replacedArgs.map(replaceStrat.execute[Exp](_)).map(x => Inhale(x)(m.pos, m.info))

        Seqn(exPres ++ inPosts)(m.pos, m.info)
    }, Traverse.Innermost)

    files foreach { fileName: String => {
      executeTest(filePrefix, fileName, strat, frontend)
    }
    }
  }

  test("ImplicationSimplification") {
    val filePrefix = "transformations\\ImplicationSimplification\\"
    val files = Seq("simple", "complex")

    // Create new strategy. Parameter is the partial function that is applied on all nodes
    val strat = ViperStrategy.Slim({
      case i@Implies(left, right) => Or(Not(left)(i.pos, i.info), right)(i.pos, i.info)
    }) traverse Traverse.BottomUp

    val strat2 = ViperStrategy.Slim({
      case o@Or(Not(f: FalseLit), right) => Or(TrueLit()(f.pos, f.info), right)(o.pos, o.info)
      case o@Or(Not(f: TrueLit), right) => Or(FalseLit()(f.pos, f.info), right)(o.pos, o.info)
      case o@Or(left, Not(f: FalseLit)) => Or(left, TrueLit()(f.pos, f.info))(o.pos, o.info)
      case o@Or(left, Not(f: TrueLit)) => Or(left, FalseLit()(f.pos, f.info))(o.pos, o.info)
    })

    val strat3 = ViperStrategy.Slim({
      case o@Or(t: TrueLit, right) => TrueLit()(o.pos, o.info)
      case o@Or(left, t: TrueLit) => TrueLit()(o.pos, o.info)
    })

    val strat4 = ViperStrategy.Slim({
      case a@Assert(t: TrueLit) => Seqn(Seq())()
    })


    val combined = strat < (strat2 + strat3) || strat4 // TODO continue here

    val frontend = new DummyFrontend
    files foreach { name => executeTest(filePrefix, name, combined, frontend) }
  }

  test("CopyPropagation") {
    val filePrefix = "transformations\\CopyPropagation\\"
    val files = Seq("simple", "complex")

    val frontend = new DummyFrontend

    val map = collection.mutable.Map.empty[LocalVar, BigInt]
    val collect = ViperStrategy.Slim({
      case p: Program => map.clear(); p // Reset map at start
      case ass@LocalVarAssign(l: LocalVar, i: IntLit) => map += (l -> i.i); ass
    }) recurseFunc { case l: LocalVarAssign => Seq(false, true) }

    val replace = ViperStrategy.Slim({
      case l: LocalVar =>
        map.get(l) match {
          case None => l
          case Some(i: BigInt) => IntLit(i)(l.pos, l.info)
        }
    })

    val fold = ViperStrategy.Slim({
      case root@Add(i1: IntLit, i2: IntLit) => IntLit(i1.i + i2.i)(root.pos, root.info)
      case root@Sub(i1: IntLit, i2: IntLit) => IntLit(i1.i - i2.i)(root.pos, root.info)
      case root@Div(i1: IntLit, i2: IntLit) => if (i2.i != 0) IntLit(i1.i / i2.i)(root.pos, root.info) else root
      case root@Mul(i1: IntLit, i2: IntLit) => IntLit(i1.i * i2.i)(root.pos, root.info)
      case root@Or(b1: BoolLit, b2: BoolLit) => BoolLit(b1.value || b2.value)(root.pos, root.info)
      case root@And(b1: BoolLit, b2: BoolLit) => BoolLit(b1.value && b2.value)(root.pos, root.info)
      case root@EqCmp(b1: BoolLit, b2: BoolLit) => BoolLit(b1.value == b2.value)(root.pos, root.info)
      case root@EqCmp(i1: IntLit, i2: IntLit) => BoolLit(i1.i == i2.i)(root.pos, root.info)
    }) traverse Traverse.TopDown // Do this top down such that we need to iterate

    val combined = (collect + replace) || fold.repeat

    files foreach { name => executeTest(filePrefix, name, combined, frontend) }
  }

  def executeTest(filePrefix: String, fileName: String, strat: StrategyInterface[Node], frontend: DummyFrontend): Unit = {

    val fileRes = getClass.getResource(filePrefix + fileName + ".sil")
    assert(fileRes != null, s"File $filePrefix$fileName not found")
    val file = Paths.get(fileRes.toURI)
    var targetNode: Node = null
    var targetRef: Node = null

    frontend.translate(file) match {
      case (Some(p), _) => targetNode = p
      case (None, errors) => println("Problem with program: " + errors)
    }
    val res = strat.execute[Program](targetNode)
    println("debug:" + res.toString())

    val fileRef = getClass.getResource(filePrefix + fileName + "Ref.sil")
    assert(fileRef != null, s"File $filePrefix$fileName Ref not found")

    val ref = Paths.get(fileRef.toURI)
    frontend.translate(ref) match {
      case (Some(p), _) => targetRef = p
      case (None, errors) => println("Problem with program: " + errors)
    }

    //  println("Old: " + targetNode.toString())
    println("New: " + res.toString)
    println("Reference: " + targetRef.toString())
    assert(res.toString == targetRef.toString(), "Files are not equal")
  }


  class DummyFrontend extends SilFrontend {
    def createVerifier(fullCmd: _root_.scala.Predef.String) = ???

    def configureVerifier(args: Seq[String]) = ???

    def translate(silverFile: Path): (Option[Program], Seq[AbstractError]) = {
      _verifier = None
      _state = TranslatorState.Initialized

      reset(silverFile) //
      translate()

      //println(s"_program = ${_program}") /* Option[Program], set if parsing and translating worked */
      //println(s"_errors = ${_errors}")   /*  Seq[AbstractError], contains errors, if encountered */

      (_program, _errors)
    }
  }

}
