/**
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rmannibucau.maven.extension.parallel;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.internal.DependencyContext;
import org.apache.maven.lifecycle.internal.MojoExecutor;
import org.apache.maven.lifecycle.internal.PhaseRecorder;
import org.apache.maven.lifecycle.internal.ProjectIndex;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

@Component(role = MojoExecutor.class)
public class ParallelMojoExecutor extends MojoExecutor {
    @Requirement
    private Logger logger;

    @Override
    public void execute(final MavenSession session, final List<MojoExecution> mojoExecutions, final ProjectIndex projectIndex)
            throws LifecycleExecutionException {
        final DependencyContext dependencyContext = newDependencyContext(session, mojoExecutions);
        final PhaseRecorder phaseRecorder = new PhaseRecorder(session.getCurrentProject());
        final MavenProject project = session.getCurrentProject();

        // we parallelize per phase - we don't parallelize phases yet.
        String previousPhase = null;
        Collection<String> parallelizable = null;
        ExecutorService executor = null;
        final Collection<Future<?>> pending = new ArrayList<>(2);
        final boolean debug = logger.isDebugEnabled();
        try {
            for (final MojoExecution mojoExecution : mojoExecutions) {
                final String currentPhase = getPhase(mojoExecution);
                if (!currentPhase.equals(previousPhase)) {
                    if (debug) {
                        logger.debug("Entering phase '" + currentPhase + "'");
                    }
                    previousPhase = currentPhase;
                    parallelizable = ofNullable(project)
                            .map(p -> p.getProperties().getProperty("rmannibucau.extension.parallelizable.mojo." + currentPhase))
                            .map(it -> Stream.of(it.trim().split(",")).map(String::trim).filter(mojo -> !mojo.isEmpty())
                                    .collect(toSet()))
                            .orElseGet(Collections::emptySet);

                    if (!pending.isEmpty()) {
                        waitFor(pending);
                    }
                }

                final String executionId = getExecutionRef(mojoExecution);
                if (debug) {
                    logger.debug("Encoutering execution '" + executionId + "'");
                }
                if (parallelizable.contains(executionId)) {
                    if (executor == null) {
                        executor = createExecutor(session);
                    }

                    pending.add(executor.submit(() -> {
                        try {
                            execute(session, mojoExecution, projectIndex, dependencyContext, phaseRecorder);
                        } catch (final LifecycleExecutionException e) {
                            throw new NestedException(e);
                        }
                    }));
                } else {
                    execute(session, mojoExecution, projectIndex, dependencyContext, phaseRecorder);
                }
            }

            if (!pending.isEmpty()) {
                waitFor(pending);
            }
        } finally {
            if (executor != null) {
                // should be immediate since we ensure to wait before that point all the submitted tasks
                executor.shutdownNow();
                try {
                    executor.awaitTermination(5, SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void waitFor(final Collection<Future<?>> pending) throws LifecycleExecutionException {
        for (final Future<?> future : pending) {
            try {
                future.get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final ExecutionException e) {
                if (NestedException.class.isInstance(e.getCause())) {
                    throw NestedException.class.cast(e.getCause()).getCause();
                }
            }
        }
        pending.clear();
    }

    private ExecutorService createExecutor(final MavenSession session) {
        return Executors.newFixedThreadPool(ofNullable(session.getCurrentProject())
                .map(p -> p.getProperties().getProperty("rmannibucau.extension.parallelizable.threads")).map(Integer::parseInt)
                .orElseGet(() -> Runtime.getRuntime().availableProcessors() + 1));
    }

    private String getPhase(MojoExecution mojoExecution) {
        return ofNullable(mojoExecution.getLifecyclePhase())
                .orElseGet(() -> ofNullable(mojoExecution.getMojoDescriptor()).map(MojoDescriptor::getPhase).orElse("default"));
    }

    private String getExecutionRef(final MojoExecution mojoExecution) {
        return ofNullable(mojoExecution.getGroupId()).orElse("org.apache.maven.plugins") + ':' + mojoExecution.getArtifactId()
                + ':' + ofNullable(mojoExecution.getMojoDescriptor().getGoal()).orElse("default") + '@'
                + mojoExecution.getExecutionId();
    }

    private static class NestedException extends RuntimeException {

        private final LifecycleExecutionException cause;

        private NestedException(final LifecycleExecutionException e) {
            super(e);
            this.cause = e;
        }

        @Override
        public LifecycleExecutionException getCause() {
            return cause;
        }
    }
}
