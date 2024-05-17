package io.ksmt.solver.maxsmt.test.configurations

import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.maxsmt.KMaxSMTContext
import io.ksmt.solver.maxsmt.solvers.KMaxSMTSolverInterface
import io.ksmt.solver.maxsmt.test.smt.KMaxSMTBenchmarkTest
import io.ksmt.solver.maxsmt.test.utils.MaxSmtSolver
import io.ksmt.solver.maxsmt.test.utils.Solver

class KDualMaxResSMTBenchmarkTest : KMaxSMTBenchmarkTest() {
    override val maxSmtCtx = KMaxSMTContext()

    override fun getSolver(solver: Solver): KMaxSMTSolverInterface<out KSolverConfiguration> {
        val smtSolver = getSmtSolver(solver)
        return getMaxSmtSolver(MaxSmtSolver.PRIMAL_DUAL_MAXRES, smtSolver)
    }
}
