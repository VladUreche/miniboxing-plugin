//
//     _____   .__         .__ ___.                    .__ scala-miniboxing.org
//    /     \  |__|  ____  |__|\_ |__    ____  ___  ___|__|  ____     ____
//   /  \ /  \ |  | /    \ |  | | __ \  /  _ \ \  \/  /|  | /    \   / ___\
//  /    Y    \|  ||   |  \|  | | \_\ \(  <_> ) >    < |  ||   |  \ / /_/  >
//  \____|__  /|__||___|  /|__| |___  / \____/ /__/\_ \|__||___|  / \___  /
//          \/          \/          \/               \/         \/ /_____/
// Copyright (c) 2011-2015 Scala Team, École polytechnique fédérale de Lausanne
//
// Authors:
//    * Vlad Ureche
//
package miniboxing.plugin
package transform
package prepare

import scala.tools.nsc.transform.TypingTransformers
import scala.tools.nsc.Phase
import scala.tools.nsc.typechecker.Analyzer

trait PrepareTreeTransformer extends TypingTransformers with ScalacCrossCompilingLayer {
  self: PrepareComponent =>

  import global._
  import definitions.{AnyTpe, Any_asInstanceOf}

  class PreparePhaseImpl(prev: Phase) extends StdPhase(prev) {
    override def name = PrepareTreeTransformer.this.phaseName
    def apply(unit: CompilationUnit): Unit = {
      val tree = afterPrepare((new PrepareAdapter).adapt(unit))
      tree.foreach(node => assert(tree.isInstanceOf[Import] || (node.tpe != null), "Stupid node: " + node + "  " + tree.isInstanceOf[Import] + "  " + (node.tpe != null)))
    }
  }

  import interop._

  class PrepareAdapter extends TweakedAnalyzer {
    var indent = 0
    override lazy val global: self.global.type = self.global

    def adapt(unit: CompilationUnit): Tree = {
      val context = rootContext(unit)
      // turnOffErrorReporting(this)(context)
      val checker = new TreeAdapter(context)
      unit.body = checker.typed(unit.body)
      unit.body
    }

    override def newTyper(context: Context): Typer =
      new TreeAdapter(context)

    class TreeAdapter(context0: Context) extends TweakedTyper(context0) {
      override protected def finishMethodSynthesis(templ: Template, clazz: Symbol, context: Context): Template =
        templ

      override protected def adapt(tree: Tree, mode: Mode, pt: Type, original: Tree = EmptyTree): Tree =
        if (tree.tpe <:< pt)
          tree
        else {
          debuglog("casting " + tree + " to " + pt)
          Apply(TypeApply(Select(tree, Any_asInstanceOf).setType(Any_asInstanceOf.tpe), List(TypeTree(pt.withoutMbFunction))).setType(pt), List()).setType(pt)
        }

      def supertyped(tree: Tree, mode: Mode, pt: Type) =
        try {
          super.typed(tree, mode, pt)
        } catch {
          case te: TypeError =>
            System.err.println(tree)
            te.printStackTrace()
            tree
        }

      override def typed(tree: Tree, mode: Mode, pt: Type): Tree = {
        val res = tree match {

          // calls such as x1.<outer>() when <outer> does not take parameters at all...
          case Apply(outer, List()) if (outer.hasSymbolField && outer.symbol.name.decoded == "<outer>") =>
            super.typed(outer, mode, pt)

          // bug #204: Superaccessors introduces asInstanceOf calls that include the @mbFunction annotation.
          // Later, when the annotated types become incompatible with the non-annotated types, the type
          // checker rejects the code saying that T => R @mbFunction does not conform to the bounds of
          // asInstanceOf, T0. To fix this, we eliminate all asInstanceOf calls where the type is annotated
          // if necessary, a cast will be introduced by the adapt method.
          case Apply(tapply @ TypeApply(Select(value, nme.asInstanceOf_), List(tpt)), List()) if tapply.symbol == definitions.Any_asInstanceOf && tpt.tpe.isMbFunction =>
            typed(value, tpt.tpe)

          case EmptyTree | TypeTree() =>
            tree

          case _ =>
            tree.setType(null)
            supertyped(tree, mode, pt)
        }

        res
      }
    }
  }
}
