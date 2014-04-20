/*
 * Copyright (c) 2013 GraphAware
 *
 * This file is part of GraphAware.
 *
 * GraphAware is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.graphaware.runtime;

import com.graphaware.runtime.config.DefaultRuntimeConfiguration;
import com.graphaware.tx.executor.single.SimpleTransactionExecutor;
import com.graphaware.tx.executor.single.VoidReturningCallback;
import org.apache.log4j.Logger;
import org.neo4j.graphdb.*;
import org.neo4j.helpers.collection.PrefetchingIterator;
import org.neo4j.kernel.GraphDatabaseAPI;

import java.util.Collection;
import java.util.Iterator;

import static com.graphaware.runtime.config.RuntimeConfiguration.*;
import static org.neo4j.tooling.GlobalGraphOperations.*;


/**
 * {@link GraphAwareRuntime} that operates on a real {@link GraphDatabaseService}.
 */
public class ProductionGraphAwareRuntime extends BaseGraphAwareRuntime implements GraphAwareRuntime {
    private static final Logger LOG = Logger.getLogger(ProductionGraphAwareRuntime.class);

    private final GraphDatabaseService database;
    private Node root; //only here until https://github.com/neo4j/neo4j/issues/1065 is resolved

    /**
     * Create a new instance of the runtime.
     *
     * @param database on which the runtime should operate.
     */
    public ProductionGraphAwareRuntime(GraphDatabaseService database) {
        super();
        this.database = database;
        registerSelfAsHandler();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void registerSelfAsHandler() {
        database.registerTransactionEventHandler(this);
        database.registerKernelEventHandler(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean databaseAvailable() {
        return database.isAvailable(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Transaction startTransaction() {
        return database.beginTx();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doInitialize(GraphAwareRuntimeModule module) {
        module.initialize(database);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doReinitialize(GraphAwareRuntimeModule module) {
        module.reinitialize(database);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doRecordInitialization(final GraphAwareRuntimeModule module, final String key) {
        new SimpleTransactionExecutor(database).executeInTransaction(new VoidReturningCallback() {
            @Override
            protected void doInTx(GraphDatabaseService database) {
                getOrCreateRoot().setProperty(key, CONFIG + module.asString());
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void removeUnusedModules(final Collection<String> unusedModules) {
        new SimpleTransactionExecutor(database).executeInTransaction(new VoidReturningCallback() {
            @Override
            protected void doInTx(GraphDatabaseService database) {
                for (String toRemove : unusedModules) {
                    LOG.info("Removing unused module " + toRemove + ".");
                    getOrCreateRoot().removeProperty(toRemove);
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void forceInitialization(final GraphAwareRuntimeModule module) {
        try (Transaction tx = database.beginTx()) {
            getOrCreateRoot().setProperty(moduleKey(module), FORCE_INITIALIZATION + System.currentTimeMillis());
            tx.success();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Node getOrCreateRoot() {
        if (root != null) {
            return root;
        }

        Iterator<Node> roots;

        if (database instanceof GraphDatabaseAPI) {
            roots = at(database).getAllNodesWithLabel(GA_ROOT).iterator();
        }
        else {
            //this is for Batch Graph Database
            roots = new RootNodeIterator(database);
        }

        if (!roots.hasNext()) {
            LOG.info("GraphAware Runtime has never been run before on this database. Creating runtime root node...");
            root = database.createNode(GA_ROOT);
        }
        else {
            root = roots.next();

            if (roots.hasNext()) {
                LOG.fatal("There is more than 1 runtime root node! Cannot start GraphAware Runtime.");
                throw new IllegalStateException("There is more than 1 runtime root node! Cannot start GraphAware Runtime.");
            }
        }

        return root;
    }

    private class RootNodeIterator extends PrefetchingIterator<Node> {

        private final Iterator<Node> nodes;

        private RootNodeIterator(GraphDatabaseService database) {
            nodes = database.getAllNodes().iterator();
        }

        @Override
        protected Node fetchNextOrNull() {
            while (nodes.hasNext()) {
                Node next = nodes.next();
                if (next.hasLabel(GA_ROOT)) {
                    return next;
                }
            }

            return null;
        }
    }
}
