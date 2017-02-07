/*
 * ProblemComponent.scala
 * One component of a problem to be solved, corresponding to a single element.
 *
 * Created By:      Avi Pfeffer (apfeffer@cra.com)
 * Creation Date:   March 1, 2015
 *
 * Copyright 2015 Avrom J. Pfeffer and Charles River Analytics, Inc.
 * See http://www.cra.com or email figaro@cra.com for information.
 *
 * See http://www.github.com/p2t2/figaro for a copy of the software license.
 */

package com.cra.figaro.algorithm.structured

import com.cra.figaro.language._
import com.cra.figaro.algorithm.lazyfactored.ValueSet
import com.cra.figaro.algorithm.factored.factors._
import com.cra.figaro.algorithm.factored.factors.factory._
import com.cra.figaro.library.collection.MakeArray
import com.cra.figaro.library.collection.FixedSizeArray
import com.cra.figaro.algorithm.factored.ParticleGenerator
import com.cra.figaro.language.Constant

/*
/** A component of a problem, created for a specific element. */
 */
class ProblemComponent[Value](val problem: Problem, val element: Element[Value]) {
  /** The current range of the element. May grow or change over time. */
  var range: ValueSet[Value] = ValueSet.withStar(Set())

  private var _variable: Variable[Value] = _

  /**
   *  The current variable representing this component in factors.
   *  This is set automatically when the range is updated.
   */
  def variable = _variable

  /**
   * A problem component is fully enumerated if its range is complete. This also means that its range cannot contain
   * star. This is always false for components associated with elements that have infinite support.
   */
  var fullyEnumerated = false

  /**
   * A problem component is fully refined if any additional refinement cannot change its range or factors. One necessary
   * condition is to be fully enumerated. Additionally, expandable components must be fully expanded (i.e. have created
   * a subproblem for each parent value), and each subproblem must be fully refined. These conditions are necessary but
   * not always sufficient to be fully refined.
   */
  var fullyRefined = false

  /**
   *  Set the variable associated with this component to the given variable.
   */
  def setVariable(v: Variable[Value]) {
    _variable = v
    problem.collection.variableToComponent += v -> this
  }

  setVariable(new Variable(range))

  /**
   * Gets the constraint factors for this component. Returns the lower bound factors unless an Upper argument is provided.
   */
  def constraintFactors(bounds: Bounds = Lower): List[Factor[Double]] = {
    val upper = bounds == Upper
    ConstraintFactory.makeFactors(problem.collection, element, upper)
  }

  /**
   *  Generate the non-constraint factors based on the current range. For most elements, this just generates the factors
   *  in the usual way. For a chain, this does not include subproblem factors. The parameterized flag indicates whether
   *  parameterized elements should have special factors created that use the MAP values of their arguments. This
   *  defaults to false.
   */
  def nonConstraintFactors(parameterized: Boolean = false): List[Factor[Double]] = {
    Factory.makeFactors(problem.collection, element, parameterized).map(_.deDuplicate)
  }

  /*
  // The current belief about this component, used for belief propagation.
  var belief: Factor[Double] = _ //StarFactory.makeStarFactor(element)(0)

  // Components that share a factor with this component. Used for belief propagation.
  var neighbors: List[ProblemComponent[_]] = List()

  // Messages queued from the neighbors to be set to the incoming messages on synchronization.
  var queuedMessages: Map[ProblemComponent[_], List[Factor[Double]]] = Map()

  // Messages received from the neighbors.
  var incomingMessages: Map[ProblemComponent[_], List[Factor[Double]]] = Map()
*/

  /**
   *  Generate a range of values for this component. Also sets the variable for this component.
   * The optional argument is the number of values to include in the range.
   * This argument is only used for atomic elements.
   * If an argument is not in the component collection, we do not generate the argument, but instead assume its only value is *.
   * This doesn't change the range of any other element or expand any subproblems.
   * The range will include * based on argument ranges including * or any subproblem not being expanded.\
   *
   */
  def generateRange(numValues: Int = ParticleGenerator.defaultMaxNumSamplesAtChain) {
    val newRange = Range(this, numValues)
    if ((newRange.hasStar ^ range.hasStar) || (newRange.regularValues != range.regularValues)) {
      range = newRange
      setVariable(new Variable(range))
    }
  }

  /*
  // Compute current beliefs about this component based on the queued messages and factors of this component.
  // Also sets the incoming messages to the queued messages.
  def computeBeliefs() {

  }

  // Send messages to the neighbors based on current beliefs and incoming messages.
  def sendMessages() {

  }
  *
  */
}

class ApplyComponent[Value](problem: Problem, element: Element[Value]) extends ProblemComponent(problem, element) {
  private var applyMap = scala.collection.mutable.Map[Any, Value]()
  def getMap() = applyMap
  def setMap(m: scala.collection.mutable.Map[Any, Value]) = applyMap = m
}

/**
 * A problem component that provides an expand method.
 * @param parent the element according to whose values this component should be expanded
 * @param element the element to which this component corresponds
 */
abstract class ExpandableComponent[ParentValue, Value](problem: Problem, parent: Element[ParentValue], element: Element[Value])
    extends ProblemComponent(problem, element) {
  /**
   * Expand for all values of the parent that were not previously expanded, based on the current range of the parent.
   */
  def expand() {
    if (problem.collection.contains(parent)) {
      val parentValues = problem.collection(parent).range.regularValues
      val unexpanded = parentValues -- subproblems.keySet
      for (parentValue <- unexpanded) expand(parentValue)
    }
  }

  /** Expand for a particular parent value. */
  def expand(parentValue: ParentValue): Unit

  val expandFunction: (ParentValue) => Element[Value]

  /**
   *  The subproblems nested inside this expandable component.
   *  They are created for particular parent values.
   */
  var subproblems: Map[ParentValue, NestedProblem[Value]] = Map()
}

/**
 * A problem component created for a chain element.
 */
class ChainComponent[ParentValue, Value](problem: Problem, val chain: Chain[ParentValue, Value])
    extends ExpandableComponent[ParentValue, Value](problem, chain.parent, chain) {

  val elementsCreated: scala.collection.mutable.Set[Element[_]] = scala.collection.mutable.Set() ++ chain.universe.contextContents(chain)

  val expandFunction = chain.get _

  /**
   *  The subproblems are defined in terms of formal variables.
   *  We need to create actual variables for each of the subproblems to replace the formal variables with in their solutions.
   */
  var actualSubproblemVariables: Map[ParentValue, Variable[Value]] = Map()

  /**
   * For a Chain, generate the range as usual, then create the actual subproblem variables for use in factor creation.
   */
  override def generateRange(numValues: Int): Unit = {
    super.generateRange(numValues)
    actualSubproblemVariables = for((parentValue, subproblem) <- subproblems) yield {
      // We need to create an actual variable to represent the outcome of the chain.
      // This will have the same range as the formal variable associated with the expansion.
      // The formal variable will be replaced with the actual variable in the solution.
      // There are two possibilities.
      // If the outcome element is defined inside the chain, it will actually be different in every expansion,
      // even though the formal variable is the same. But if the outcome element is defined outside the chain,
      // it is a global that will be the same every time, so the actual variable is the variable of this global.
      val formalVar = Factory.getVariable(problem.collection, subproblem.target)
      val actualVar =
        if(subproblem.global(formalVar)) formalVar
        else Factory.makeVariable(problem.collection, formalVar.valueSet)
      parentValue -> actualVar
    }
  }

  /**
   *  Create a subproblem for a particular parent value.
   *  Memoized.
   */
  def expand(parentValue: ParentValue) {
    val subproblem = problem.collection.expansion(this, chain.chainFunction, parentValue)
    val chainContextElements = chain.universe.contextContents(chain)
    val remainingElements = chainContextElements.filter(!elementsCreated.contains(_))
    remainingElements.foreach(subproblem.add(_))
    elementsCreated ++= remainingElements
    subproblems += parentValue -> subproblem
  }

  /*
   * Tests if we can use the optimized Chain factor. If all the subproblems have been eliminated completely and use no
   * globals, we can use the new chain method.
   */
  private[figaro] def allSubproblemsEliminatedCompletely: Boolean = {
    // Every subproblem must be completely eliminated
    subproblems.values.forall { subproblem =>
      // The subproblem must be solved and have no globals in its solution
      subproblem.solved && {
        val factors = subproblem.solution
        val vars = factors.flatMap(_.variables)
        val comps = vars.map(problem.collection.variableToComponent(_))
        // The first part checks that the subproblem target is not global
        // The second part checks that the factors contain no additional global variables
        problem.collection(subproblem.target).problem == subproblem && comps.forall(subproblem.components.contains)
      }
    }
  }

  /*
  def raiseSubproblemSolution(parentValue: ParentValue, subproblem: NestedProblem[Value]): Unit = {
    val factors = for {
      factor <- subproblem.solution
    } yield Factory.replaceVariable(factor, problem.collection(subproblem.target).variable, actualSubproblemVariables(parentValue))
    nonConstraintFactors = factors ::: nonConstraintFactors
  }

  // Raise the given subproblem into this problem. Note that factors for the chain must have been created already
  // This probably needs some more thought!
  // This assumes that components in nested problems do not have evidence on them, and therefore we don't need to raise
  // the constraint factors.
  def raise(parentValue: ParentValue): Unit = {

    if (subproblems.contains(parentValue) && !subproblems(parentValue).solved) {

      val subproblem = subproblems(parentValue)
      val comp = subproblem.collection(subproblem.target)
      val NCF = subproblem.components.flatMap(c => c.nonConstraintFactors.map(Factory.replaceVariable(_, comp.variable, actualSubproblemVariables(parentValue))))

      nonConstraintFactors = nonConstraintFactors ::: NCF
    }
  }

  // Raise all subproblems into this problem
  def raise() { subproblems.foreach(sp => raise(sp._1)) }
  */

}

/**
 * A problem component for a MakeArray element.
 */
class MakeArrayComponent[Value](problem: Problem, val makeArray: MakeArray[Value])
    extends ExpandableComponent[Int, FixedSizeArray[Value]](problem, makeArray.numItems, makeArray) {
  /** The maximum number of items expanded so far. */
  var maxExpanded = 0

  // This isn't really needed in MakeArray since the main expand function won't call this.
  val expandFunction = (i: Int) => Constant(makeArray.makeValues(i).regularValues.head)

  /**
   * Ensure that the given number of items is expanded.
   */
  def expand(n: Int) {
    // Make sure the first n items are added to the component collection.
    // Any newly added items will be added to this problem.
    for { i <- maxExpanded until n } {
      val item = makeArray.items(i)
      if (!problem.collection.contains(item)) problem.add(item)
    }
    maxExpanded = maxExpanded.max(n)
  }

  /**
   * Expand all the potential items according to the maximum value in the range of the MakeArray's number of items.
   */
  override def expand {
    if (problem.collection.contains(makeArray.numItems)) {
      expand(problem.collection(makeArray.numItems).range.regularValues.max)
    }
  }
}
