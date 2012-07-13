/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.planner.core.heuristic.selector.value.chained;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.drools.planner.core.domain.entity.PlanningEntityDescriptor;
import org.drools.planner.core.domain.variable.PlanningVariableDescriptor;
import org.drools.planner.core.heuristic.selector.AbstractSelector;
import org.drools.planner.core.heuristic.selector.common.SelectionCacheLifecycleBridge;
import org.drools.planner.core.heuristic.selector.common.SelectionCacheLifecycleListener;
import org.drools.planner.core.heuristic.selector.common.SelectionCacheType;
import org.drools.planner.core.heuristic.selector.common.UpcomingSelectionIterator;
import org.drools.planner.core.heuristic.selector.value.EntityIgnoringValueIterator;
import org.drools.planner.core.heuristic.selector.value.IteratorToValueIteratorBridge;
import org.drools.planner.core.heuristic.selector.value.ValueSelector;
import org.drools.planner.core.move.Move;
import org.drools.planner.core.score.director.ScoreDirector;
import org.drools.planner.core.solver.DefaultSolverScope;

/**
 * This is the common {@link SubChainSelector} implementation.
 */
public class DefaultSubChainSelector extends AbstractSelector
        implements SubChainSelector, SelectionCacheLifecycleListener {

    protected final ValueSelector valueSelector;
    protected final boolean randomSelection;

    protected final int minimumSubChainSize = 1;

    protected List<SubChain> anchorChainList = null;

    public DefaultSubChainSelector(ValueSelector valueSelector, boolean randomSelection) {
        this.valueSelector = valueSelector;
        this.randomSelection = randomSelection;
        if (!valueSelector.getVariableDescriptor().isChained()) {
            throw new IllegalArgumentException("The valueSelector (" + valueSelector
                    + ") must have a chained variableDescriptor chained ("
                    + valueSelector.getVariableDescriptor().isChained()
                    + ") on the class (" + getClass().getName() + ").");
        }
        if (valueSelector.isNeverEnding()) {
            throw new IllegalStateException("The valueSelector (" + valueSelector + ") has neverEnding ("
                    + valueSelector.isNeverEnding() + ") on a class (" + getClass().getName() + ") instance.");
        }
        solverPhaseLifecycleSupport.addEventListener(valueSelector);
        solverPhaseLifecycleSupport.addEventListener(new SelectionCacheLifecycleBridge(SelectionCacheType.STEP, this));
//        if (minimumSubChainSize > maximumSubChainSize) {
//            throw new IllegalStateException("The minimumSubChainSize (" + minimumSubChainSize
//                    + ") must be at least maximumSubChainSize (" + maximumSubChainSize + ").");
//        }
    }

    public PlanningVariableDescriptor getVariableDescriptor() {
        return valueSelector.getVariableDescriptor();
    }

    // ************************************************************************
    // Cache lifecycle methods
    // ************************************************************************

    public void constructCache(DefaultSolverScope solverScope) {
        ScoreDirector scoreDirector = solverScope.getScoreDirector();
        PlanningVariableDescriptor variableDescriptor = valueSelector.getVariableDescriptor();
        Class<?> entityClass = variableDescriptor.getPlanningEntityDescriptor().getPlanningEntityClass();
        long valueSize = valueSelector.getSize();
        // Fail-fast when anchorChainSize could ever be too big
        if (valueSize > (long) Integer.MAX_VALUE) {
            throw new IllegalStateException("The subChainSelector (" + this + ") has a valueSelector ("
                    + valueSelector + ") with valueSize (" + valueSize
                    + ") which is higher than Integer.MAX_VALUE.");
        }
        // Temporary LinkedList to avoid using a bad initialCapacity
        List<Object> anchorList = new LinkedList<Object>();
        for (Object value : valueSelector) {
            if (!entityClass.isAssignableFrom(value.getClass())) {
                anchorList.add(value);
            }
        }
        anchorChainList = new ArrayList<SubChain>(anchorList.size());
        int anchorChainInitialCapacity = ((int) valueSize / anchorList.size()) + 1;
        for (Object anchor : anchorList) {
            List<Object> anchorChain = new ArrayList<Object>(anchorChainInitialCapacity);
            Object trailingEntity = scoreDirector.getTrailingEntity(variableDescriptor, anchor);
            while (trailingEntity != null) {
                anchorChain.add(trailingEntity);
                trailingEntity = scoreDirector.getTrailingEntity(variableDescriptor, trailingEntity);
            }
            anchorChainList.add(new SubChain(anchorChain));
        }
    }

    public void disposeCache(DefaultSolverScope solverScope) {
        anchorChainList = null;
    }

    // ************************************************************************
    // Worker methods
    // ************************************************************************

    public boolean isContinuous() {
        return false;
    }

    public boolean isNeverEnding() {
        return randomSelection;
    }

    public long getSize() {
        long size = 0L;
        for (SubChain anchorChain : anchorChainList) {
            long anchorChainSize = anchorChain.getValueList().size();
            size += anchorChainSize * (anchorChainSize - 1L) / 2L;
        }
        return size;
    }

    public Iterator<SubChain> iterator() {
        if (!randomSelection) {
            return new OriginalSubChainIterator(anchorChainList.iterator());
        } else {
            throw new UnsupportedOperationException(""); // TODO
        }
    }

    private class OriginalSubChainIterator extends UpcomingSelectionIterator<SubChain> {

        private final Iterator<SubChain> anchorChainIterator;
        private List<Object> anchorChain;
        private int anchorChainSize;
        private int fromIndex = 0;
        private int toIndex = 0;

        public OriginalSubChainIterator(Iterator<SubChain> anchorChainIterator) {
            this.anchorChainIterator = anchorChainIterator;
            anchorChainSize = 0;
            fromIndex = -1;
            toIndex = -1;
            createUpcomingSelection();
        }

        protected void createUpcomingSelection() {
            toIndex++;
            if (toIndex >= anchorChainSize) {
                fromIndex++;
                toIndex = fromIndex + minimumSubChainSize;
                while (toIndex >= anchorChainSize) {
                    if (!anchorChainIterator.hasNext()) {
                        upcomingSelection = null;
                        return;
                    }
                    anchorChain = anchorChainIterator.next().getValueList();
                    anchorChainSize = anchorChain.size();
                    fromIndex = 0;
                    toIndex = fromIndex + minimumSubChainSize;
                }
            }
            upcomingSelection = new SubChain(anchorChain.subList(fromIndex, toIndex));
        }

    }

    @Override
    public String toString() {
        return "DefaultSubChainSelector(" + valueSelector + ")";
    }

}