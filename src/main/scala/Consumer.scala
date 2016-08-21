/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon

import org.slf4s.Logging
import viper.silver.ast
import viper.silver.verifier.{VerificationError, PartialVerificationError}
import viper.silver.verifier.reasons._
import viper.silicon.interfaces.state._
import viper.silicon.interfaces.{Consumer, Evaluator, VerificationResult, Failure}
import viper.silicon.interfaces.decider.Decider
import viper.silicon.interfaces.state.factoryUtils.Ø
import viper.silicon.reporting.Bookkeeper
import viper.silicon.state.{SymbolConvert, DefaultContext, MagicWandChunk}
import viper.silicon.state.terms._
import viper.silicon.state.terms.predef.`?r`
import viper.silicon.supporters._
import viper.silicon.supporters.qps.{QuantifiedChunkSupporter, QuantifiedPredicateChunkSupporter}

trait DefaultConsumer[ST <: Store[ST], H <: Heap[H], S <: State[ST, H, S]]
    extends Consumer[ST, H, S, DefaultContext[H]]
    { this: Logging with Evaluator[ST, H, S, DefaultContext[H]]
                    with Brancher[ST, H, S, DefaultContext[H]]
                    with LetHandler[ST, H, S, DefaultContext[H]]
                    with MagicWandSupporter[ST, H, S]
                    with HeuristicsSupporter[ST, H, S]
                    with LetHandler[ST, H, S, DefaultContext[H]] =>

  private[this] type C = DefaultContext[H]

  protected implicit val manifestH: Manifest[H]

  protected val decider: Decider[ST, H, S, C]
  protected val symbolConverter: SymbolConvert
  protected val quantifiedChunkSupporter: QuantifiedChunkSupporter[ST, H, S, C]
  protected val quantifiedPredicateChunkSupporter: QuantifiedPredicateChunkSupporter[ST, H, S, C]
  protected val stateFormatter: StateFormatter[ST, H, S, String]
  protected val bookkeeper: Bookkeeper
  protected val config: Config
  protected val chunkSupporter: ChunkSupporter[ST, H, S, C]

  import decider.assume
  import stateFactory._
  import symbolConverter.toSort

  /*
   * ATTENTION: The DirectChunks passed to the continuation correspond to the
   * chunks as they existed when the consume took place. More specifically,
   * the amount of permissions that come with these chunks is NOT the amount
   * that has been consumed, but the amount that was found in the heap.
   */
  def consume(σ: S, p: Term, φ: ast.Exp, pve: PartialVerificationError, c: C)
             (Q: (S, Term, C) => VerificationResult)
             : VerificationResult =

    consume(σ, σ.h, p, φ.whenExhaling, pve, c)((h1, t, c1) =>
      Q(σ \ h1, t, c1))

  def consumes(σ: S,
               p: Term,
               φs: Seq[ast.Exp],
               pvef: ast.Exp => PartialVerificationError,
               c: C)
              (Q: (S, Term, C) => VerificationResult)
              : VerificationResult =

    consumes(σ, σ.h, p, φs map (_.whenExhaling), pvef, c)(Q)

  private def consumes(σ: S,
                       h: H,
                       p: Term,
                       φs: Seq[ast.Exp],
                       pvef: ast.Exp => PartialVerificationError,
                       c: C)
                       (Q: (S, Term, C) => VerificationResult)
                       : VerificationResult =

    /* Note: See the code comment about produce vs. produces in
     * DefaultProducer.produces. The same applies to consume vs. consumes.
     */

    if (φs.isEmpty)
      Q(σ \ h, Unit, c)
    else {
      val φ = φs.head

      if (φs.tail.isEmpty)
        consume(σ, h, p, φ, pvef(φ), c)((h1, s1, c1) =>
          Q(σ \ h1, s1, c1))
      else
        consume(σ, h, p, φ, pvef(φ), c)((h1, s1, c1) =>
          consumes(σ, h1, p, φs.tail, pvef, c1)((h2, s2, c2) => {
            Q(h2, Combine(s1, s2), c2)}))
    }

  protected def consume(σ: S, h: H, p: Term, φ: ast.Exp, pve: PartialVerificationError, c: C)
                       (Q: (H, Term, C) => VerificationResult)
                       : VerificationResult = {

    if (!φ.isInstanceOf[ast.And]) {
      log.debug(s"\nCONSUME ${utils.ast.sourceLineColumn(φ)}: $φ")
      log.debug(stateFormatter.format(σ, decider.π))
      log.debug("h = " + stateFormatter.format(h))
      if (c.reserveHeaps.nonEmpty)
        log.debug("hR = " + c.reserveHeaps.map(stateFormatter.format).mkString("", ",\n     ", ""))
    }

    val consumed = φ match {
      case ast.And(a1, a2) if !φ.isPure || config.handlePureConjunctsIndividually() =>
        consume(σ, h, p, a1, pve, c)((h1, s1, c1) =>
          consume(σ, h1, p, a2, pve, c1)((h2, s2, c2) => {
            Q(h2, Combine(s1, s2), c2)}))

      case ast.Implies(e0, a0) if !φ.isPure =>
        eval(σ, e0, pve, c)((t0, c1) =>
          branch(σ, t0, c1,
            (c2: C) => consume(σ, h, p, a0, pve, c2)(Q),
            (c2: C) => Q(h, Unit, c2)))

      case ast.CondExp(e0, a1, a2) if !φ.isPure =>
        eval(σ, e0, pve, c)((t0, c1) =>
          branch(σ, t0, c1,
            (c2: C) => consume(σ, h, p, a1, pve, c2)(Q),
            (c2: C) => consume(σ, h, p, a2, pve, c2)(Q)))

      case ast.utility.QuantifiedPermissions.QPForall(qvar, cond, rcvr, field, loss, forall, fa) =>
        val qid = s"prog.l${utils.ast.sourceLine(forall)}"
        evalQuantified(σ, Forall, Seq(qvar.localVar), Seq(cond), Seq(rcvr, loss), Nil, qid, pve, c){
          case (Seq(tQVar), Seq(tCond), Seq(tRcvr, tLoss), _, tAuxQuantNoTriggers, c1) =>
            decider.assert(σ, Forall(tQVar, Implies(tCond, perms.IsNonNegative(tLoss)), Nil)) {
              case true =>
                val hints = quantifiedChunkSupporter.extractHints(Some(tQVar), Some(tCond), tRcvr)
                val chunkOrderHeuristics = quantifiedChunkSupporter.hintBasedChunkOrderHeuristic(hints)
                val invFct =
                  quantifiedChunkSupporter.getFreshInverseFunction(tQVar, tRcvr, tCond, c1.quantifiedVariables)
                decider.prover.logComment("Nested auxiliary terms")
                assume(tAuxQuantNoTriggers.copy(vars = invFct.invOfFct.vars, triggers = invFct.invOfFct.triggers))
                /* TODO: Can we omit/simplify the injectivity check in certain situations? */
                val receiverInjective = quantifiedChunkSupporter.injectivityAxiom(Seq(tQVar), tCond, Seq(tRcvr))
                decider.prover.logComment("Check receiver injectivity")
                decider.assert(σ, receiverInjective) {
                  case true =>
                    decider.prover.logComment("Definitional axioms for inverse functions")
                    assume(invFct.definitionalAxioms)
                    val inverseReceiver = invFct(`?r`) // e⁻¹(r)
                    quantifiedChunkSupporter.splitLocations(σ, h, field, Some(tQVar), inverseReceiver, tCond, tRcvr, PermTimes(tLoss, p), chunkOrderHeuristics, c1) {
                      case Some((h1, ch, fvfDef, c2)) =>
                        val fvfDomain = if (c2.fvfAsSnap) fvfDef.domainDefinitions(invFct) else Seq.empty
                        decider.prover.logComment("Definitional axioms for field value function")
                        assume(fvfDomain ++ fvfDef.valueDefinitions)
                        val fr1 = c2.functionRecorder.recordQPTerms(c2.quantifiedVariables,
                                                                    decider.pcs.branchConditions,
                                                                    invFct.definitionalAxioms ++ fvfDomain ++ fvfDef.valueDefinitions)
                        val fr2 = if (true/*fvfDef.freshFvf*/) fr1.recordFvf(field, fvfDef.fvf) else fr1
                        val c3 = c2.copy(functionRecorder = fr2)
                        Q(h1, ch.fvf.convert(sorts.Snap), c3)
                      case None =>
                        Failure(pve dueTo InsufficientPermission(fa))}
                  case false =>
                    Failure(pve dueTo ReceiverNotInjective(fa))}
              case false =>
                Failure(pve dueTo NegativePermission(loss))}}
      case ast.utility.QuantifiedPermissions.QPPForall(qvar, cond, args, predname, loss, forall, predAccPred) =>
        println("exhaling Quantifier: ")
        println(predAccPred)
        val predicate = c.program.findPredicate(predname)
        val qid = s"prog.l${utils.ast.sourceLine(forall)}"
        evalQuantified(σ, Forall, Seq(qvar.localVar), Seq(cond), args ++ Seq(loss) , Nil, qid, pve, c) {
          case (Seq(tQVar), Seq(tCond), tArgsGain, _, tAuxQuantNoTriggers, c1) =>

            val (tArgs, Seq(tLoss)) = tArgsGain.splitAt(args.size)
            decider.assert(σ, Forall(tQVar, Implies(tCond, perms.IsNonNegative(tLoss)), Nil)) {
              case true =>

                val hints = quantifiedPredicateChunkSupporter.extractHints(Some(tQVar), Some(tCond), tArgs)
                val chunkOrderHeuristics = quantifiedPredicateChunkSupporter.hintBasedChunkOrderHeuristic(hints)
                val (invFct, neutralArgs) = quantifiedPredicateChunkSupporter.getFreshInverseFunction(tQVar, predicate, tArgs, tCond, c1.quantifiedVariables)
                val formalVars:Seq[Var] = predicate.formalArgs map (arg => decider.fresh(arg.name, toSort(arg.typ)))
                decider.prover.logComment("Nested auxiliary terms")
                assume(tAuxQuantNoTriggers.copy(vars = invFct.invOfFct.vars, triggers = invFct.invOfFct.triggers))
                val isInjective = quantifiedPredicateChunkSupporter.injectivityAxiom(Seq(tQVar), tCond, tArgs)
                decider.prover.logComment("Check receiver injectivity")
                decider.assert(σ, isInjective) {
                  case true =>
                    decider.prover.logComment("Definitional axioms for inverse functions")
                    assume(invFct.definitionalAxioms)
                    val inversePredicate = invFct(neutralArgs) // e⁻¹(arg1, ..., argn)
                    quantifiedPredicateChunkSupporter.splitLocations(σ, h, predicate, Some(tQVar),formalVars,  tArgs, tCond, PermTimes(tLoss, p), chunkOrderHeuristics, c1) {
                      case Some((h1, ch, psfDef, c2)) =>
                        println(h1)
                        println(ch)
                        println(psfDef)
                        println(c2)
                        val psfDomain = if (c2.psfAsSnap) psfDef.domainDefinitions(invFct) else Seq.empty
                        decider.prover.logComment("Definitional axioms for field value function")
                       assume(psfDomain ++ psfDef.snapDefinitions)
                        val fr1 = c2.functionRecorder.recordQPTerms(c2.quantifiedVariables,
                          decider.pcs.branchConditions,
                          invFct.definitionalAxioms ++ psfDomain ++ psfDef.snapDefinitions)
                        val fr2 = if (true/*fvfDef.freshFvf*/) fr1.recordPsf(predicate, psfDef.psf) else fr1
                        val c3 = c2.copy(functionRecorder = fr2)
                          Q(h1, ch.psf.convert(sorts.Snap), c3)
                      case None =>
                        println("splitLocations returned nothing")
                        Failure(pve dueTo InsufficientPermission(predAccPred.loc))}
                  case false =>
                    println("not injective");
                    Failure(pve dueTo ReceiverNotInjective(predAccPred.loc))}
              case false =>
                println("PermNonNegative"); Failure(pve dueTo NegativePermission(loss))}}
      case ast.AccessPredicate(fa @ ast.FieldAccess(eRcvr, field), perm)
          if c.qpFields.contains(field) =>
        eval(σ, eRcvr, pve, c)((tRcvr, c1) =>
          eval(σ, perm, pve, c1)((tPerm, c2) => {
            val hints = quantifiedChunkSupporter.extractHints(None, None, tRcvr)
            val chunkOrderHeuristics = quantifiedChunkSupporter.hintBasedChunkOrderHeuristic(hints)
            quantifiedChunkSupporter.splitSingleLocation(σ, h, field, tRcvr, PermTimes(tPerm, p), chunkOrderHeuristics, c2) {
              case Some((h1, ch, fvfDef, c3)) =>
                val fvfDomain = if (c3.fvfAsSnap) fvfDef.domainDefinitions else Seq.empty
                assume(fvfDomain ++ fvfDef.valueDefinitions)
                Q(h1, ch.valueAt(tRcvr), c3)
              case None => Failure(pve dueTo InsufficientPermission(fa))
            }}))
      case ast.AccessPredicate(pa @ ast.PredicateAccess(eArgs, predname), perm)
        if c.qpPredicates.contains(c.program.findPredicate(predname)) =>
        //TODO nadmuell: implement consuming single prediate under quantifier
        val predicate = c.program.findPredicate(predname)
        val formalVars:Seq[Var] = predicate.formalArgs map (arg => decider.fresh(arg.name, toSort(arg.typ)))

        evals(σ, eArgs, _ => pve, c)((tArgs, c1) =>
          eval(σ, perm, pve, c1)((tPerm, c2) => {
            val hints = quantifiedPredicateChunkSupporter.extractHints(None, None, tArgs)
            val chunkOrderHeuristics = quantifiedPredicateChunkSupporter.hintBasedChunkOrderHeuristic(hints)
            quantifiedPredicateChunkSupporter.splitSingleLocation(σ, h, predicate, tArgs, formalVars, PermTimes(tPerm, p), chunkOrderHeuristics, c2) {
              case Some((h1, ch, psfDef, c3)) =>
                val psfDomain = if (c3.psfAsSnap) psfDef.domainDefinitions else Seq.empty
                assume(psfDomain ++ psfDef.snapDefinitions)
                Q(h1, ch.valueAt(tArgs), c3)
              case None => Failure(pve dueTo InsufficientPermission(pa))
            }}))

      case let: ast.Let if !let.isPure =>
        handle[ast.Exp](σ, let, pve, c)((γ1, body, c1) => {
          val c2 =
            if (c1.recordEffects)
              c1.copy(letBoundVars = c1.letBoundVars ++ γ1.values)
            else
              c1
          consume(σ \+ γ1, h, p, body, pve, c2)(Q)})

      case ast.AccessPredicate(locacc, perm) =>
        eval(σ, perm, pve, c)((tPerm, c1) =>
          evalLocationAccess(σ, locacc, pve, c1)((name, args, c2) =>
            decider.assert(σ, perms.IsNonNegative(tPerm)){
              case true =>
                chunkSupporter.consume(σ, h, name, args, PermTimes(p, tPerm), pve, c2, locacc, Some(φ))(Q)
              case false =>
                Failure(pve dueTo NegativePermission(perm))}))

      case _: ast.InhaleExhaleExp =>
        Failure(utils.consistency.createUnexpectedInhaleExhaleExpressionError(φ))

      /* Handle wands or wand-typed variables */
      case _ if φ.typ == ast.Wand && magicWandSupporter.isDirectWand(φ) =>
        def QL(σ: S, h: H, chWand: MagicWandChunk, wand: ast.MagicWand, ve: VerificationError, c: C) = {
          heuristicsSupporter.tryOperation[H, Term](s"consume wand $wand")(σ, h, c)((σ, h, c, QS) => {
            val hs =
              if (c.exhaleExt) c.reserveHeaps
              else Stack(h)

            /* TODO: Consuming a wand chunk, respectively, transferring it if c.exhaleExt
             *       is true, should be implemented on top of MagicWandSupporter.transfer,
             *       or even on ChunkSupporter.consume.
             */
            magicWandSupporter.doWithMultipleHeaps(hs, c)((h1, c1) =>
              magicWandSupporter.getMatchingChunk(σ, h1, chWand, c1) match {
                case someChunk @ Some(ch) => (someChunk, h1 - ch, c1)
                case _ => (None, h1, c1)
              }
            ){case (Some(ch), hs1, c1) =>
                assert(c1.exhaleExt == c.exhaleExt)
                if (c.exhaleExt) {
                  /* transfer: move ch into h = σUsed*/
                  assert(hs1.size == c.reserveHeaps.size)
                  val topReserveHeap = hs1.head + ch
                  QS(h /*+ ch*/, decider.fresh(sorts.Snap), c1.copy(reserveHeaps = topReserveHeap +: hs1.tail))
                } else {
                  assert(hs1.size == 1)
                  assert(c.reserveHeaps == c1.reserveHeaps)
                  QS(hs1.head, decider.fresh(sorts.Snap), c1)
                }

              case _ => Failure(ve)}
          })(Q)
        }

        φ match {
          case wand: ast.MagicWand =>
            magicWandSupporter.createChunk(σ, wand, pve, c)((chWand, c1) => {
              val ve = pve dueTo MagicWandChunkNotFound(wand)
              QL(σ, h, chWand, wand, ve, c1)})
          case v: ast.AbstractLocalVar =>
            val tWandChunk = σ.γ(v).asInstanceOf[MagicWandChunkTerm].chunk
            val ve = pve dueTo NamedMagicWandChunkNotFound(v)
            QL(σ, h, tWandChunk, tWandChunk.ghostFreeWand, ve, c)
          case _ => sys.error(s"Expected a magic wand, but found node $φ")
        }

      case pckg @ ast.PackagingGhostOp(eWand, eIn) =>
        assert(c.exhaleExt)
        assert(c.reserveHeaps.head.values.isEmpty)
        /** TODO: [[viper.silicon.supporters.HeuristicsSupporter.heuristicsSupporter.packageWand]]
          *       Is essentially a copy of the code here. Re-use code to avoid running out of sync!
          */
        magicWandSupporter.packageWand(σ, eWand, pve, c)((chWand, c1) => {
          val hOps = c1.reserveHeaps.head + chWand
          val c2 = c1.copy(exhaleExt = true,
                           reserveHeaps = H() +: hOps +: c1.reserveHeaps.tail,
                           lhsHeap = None)
          assert(c2.reserveHeaps.length == c.reserveHeaps.length)
          assert(c2.consumedChunks.length == c.consumedChunks.length)
          assert(c2.consumedChunks.length == c2.reserveHeaps.length - 1)
          val σEmp = Σ(σ.γ, Ø, σ.g)
          consume(σEmp, σEmp.h, FullPerm(), eIn, pve, c2)((h3, _, c3) =>
            Q(h3, decider.fresh(sorts.Snap), c3))})

      case ast.ApplyingGhostOp(eWandOrVar, eIn) =>
        val (eWand, eLHSAndWand, γ1) = eWandOrVar match {
          case _eWand: ast.MagicWand =>
            (_eWand, ast.And(_eWand.left, _eWand)(_eWand.left.pos, _eWand.left.info), σ.γ)
          case v: ast.AbstractLocalVar =>
            val chWand = σ.γ(v).asInstanceOf[MagicWandChunkTerm].chunk
            val _eWand = chWand.ghostFreeWand
            (_eWand, ast.And(_eWand.left, _eWand)(v.pos, v.info), Γ(chWand.bindings))
              /* Note that wand reference v is most likely not bound in tChunk.bindings.
               * Since wands cannot be recursive, this shouldn't be a problem,
               * as long as v doesn't need to be looked during
               * magicWandSupporter.applyingWand (for whatever reason).
               */
          case _ => sys.error(s"Expected a magic wand, but found node $φ")
        }

        heuristicsSupporter.tryOperation[S, H](s"applying $eWand")(σ, h, c)((σ, h, c, QS) =>
          magicWandSupporter.applyingWand(σ, γ1, eWand, eLHSAndWand, pve, c)(QS)){case (σ1, h1, c1) =>
            consume(σ1, h1, FullPerm(), eIn, pve, c1)((h4, _, c4) =>
              Q(h4, decider.fresh(sorts.Snap), c4))}

      case ast.FoldingGhostOp(acc @ ast.PredicateAccessPredicate(ast.PredicateAccess(eArgs, predicateName), ePerm),
                              eIn) =>

        heuristicsSupporter.tryOperation[S, H](s"folding $acc")(σ, h, c)((σ, h, c, QS) =>
          magicWandSupporter.foldingPredicate(σ, acc, pve, c)(QS)){case (σ1, h1, c1) =>
            consume(σ1, h1, FullPerm(), eIn, pve, c1)((h4, _, c4) =>
              Q(h4, decider.fresh(sorts.Snap), c4))}

      case ast.UnfoldingGhostOp(acc @ ast.PredicateAccessPredicate(ast.PredicateAccess(eArgs, predicateName), ePerm),
                                eIn) =>

        heuristicsSupporter.tryOperation[S, H](s"unfolding $acc")(σ, h, c)((σ, h, c, QS) =>
          magicWandSupporter.unfoldingPredicate(σ, acc, pve, c)(QS)){case (σ1, h1, c1) =>
            consume(σ1, h1, FullPerm(), eIn, pve, c1)((h4, _, c4) =>
              Q(h4, decider.fresh(sorts.Snap), c4))}

      case _ =>
        evalAndAssert(σ, h, φ, pve, c)((h1, t, c1) => {
          Q(h1, t, c1)
        })
    }

    consumed
  }

  /* The expression is evaluated in the initial heap (σ.h), partially consumed heap (h)
   * is made available to the evaluation via the context (C.partiallyConsumedHeap).
   */
  private def evalAndAssert(σ: S, h: H, e: ast.Exp, pve: PartialVerificationError, c: C)
                           (Q: (H, Term, C) => VerificationResult)
                           : VerificationResult = {

    /* Switch to the eval heap (σUsed) of magic wand's exhale-ext, if necessary.
     * This is done here already (the evaluator would do it as well) to ensure that the eval
     * heap is compressed by tryOrFail if the assertion fails.
     */
    val σ1 = σ \ magicWandSupporter.getEvalHeap(σ, c)
    val c1 = c.copy(reserveHeaps = Nil, exhaleExt = false, partiallyConsumedHeap = Some(h))

    decider.tryOrFail[S](σ1, c1)((σ2, c2, QS, QF) => {
      eval(σ2, e, pve, c2)((t, c3) =>
        decider.assert(σ2, t) {
          case true =>
            assume(t)
            QS(σ2, c3)
          case false =>
            QF(Failure(pve dueTo AssertionFalse(e)))
        })
    })((_, c2) => {
      val c3 = c2.copy(reserveHeaps = c.reserveHeaps,
                       exhaleExt = c.exhaleExt,
                       partiallyConsumedHeap = c.partiallyConsumedHeap)
      Q(h, Unit, c3)
    })
  }
}
