/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.ProjectGenerator;
import com.facebook.buck.apple.WorkspaceAndProjectGenerator;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.intellij.Project;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.AssociatedTargetNodePredicate;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ProjectConfig;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProjectCommand extends AbstractCommandRunner<ProjectCommandOptions> {

  private static final Logger LOG = Logger.get(ProjectCommand.class);

  /**
   * Include java library targets (and android library targets) that use annotation
   * processing.  The sources generated by these annotation processors is needed by
   * IntelliJ.
   */
  private static final Predicate<TargetNode<?>> ANNOTATION_PREDICATE =
      new Predicate<TargetNode<?>>() {
        @Override
        public boolean apply(TargetNode<?> input) {
          if (input.getType() != JavaLibraryDescription.TYPE) {
            return false;
          }
          JavaLibraryDescription.Arg arg = ((JavaLibraryDescription.Arg) input.getConstructorArg());
          return !arg.annotationProcessors.get().isEmpty();
        }
      };

  private static final String XCODE_PROCESS_NAME = "Xcode";

  private final TargetGraphTransformer<ActionGraph> targetGraphTransformer;

  public ProjectCommand(CommandRunnerParams params) {
    super(params);

    this.targetGraphTransformer = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer());
  }

  @Override
  ProjectCommandOptions createOptions(BuckConfig buckConfig) {
    return new ProjectCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    if (options.getIde() == ProjectCommandOptions.Ide.XCODE) {
      checkForAndKillXcodeIfRunning(options.getIdePrompt());
    }

    ImmutableSet<BuildTarget> passedInTargetsSet =
        getBuildTargets(options.getArgumentsFormattedAsBuildTargets());
    ProjectGraphParser projectGraphParser = ProjectGraphParsers.createProjectGraphParser(
        getParser(),
        new ParserConfig(options.getBuckConfig()),
        getBuckEventBus(),
        console,
        environment,
        options.getEnableProfiling());

    TargetGraph projectGraph = projectGraphParser.buildTargetGraphForTargetNodeSpecs(
        getTargetNodeSpecsForIde(
            options.getIde(),
            passedInTargetsSet,
            getProjectFilesystem().getIgnorePaths()));

    ProjectPredicates projectPredicates = ProjectPredicates.forIde(options.getIde());

    ImmutableSet<BuildTarget> graphRoots;
    if (!passedInTargetsSet.isEmpty()) {
      graphRoots = passedInTargetsSet;
    } else {
      graphRoots = getRootsFromPredicate(
          projectGraph,
          projectPredicates.getProjectRootsPredicate());
    }

    TargetGraphAndTargets targetGraphAndTargets = createTargetGraph(
          projectGraph,
          graphRoots,
          projectGraphParser,
          projectPredicates.getAssociatedProjectPredicate(),
          options.isWithTests(),
          options.getIde(),
          getProjectFilesystem().getIgnorePaths());

    if (options.getDryRun()) {
      for (TargetNode<?> targetNode : targetGraphAndTargets.getTargetGraph().getNodes()) {
        console.getStdOut().println(targetNode.toString());
      }

      return 0;
    }

    switch (options.getIde()) {
      case INTELLIJ:
        return runIntellijProjectGenerator(
            projectGraph,
            targetGraphAndTargets,
            passedInTargetsSet,
            options);
      case XCODE:
        return runXcodeProjectGenerator(
            targetGraphAndTargets,
            passedInTargetsSet,
            options);
      default:
        // unreachable
        throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
    }
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runIntellijProjectGenerator(
      TargetGraph projectGraph,
      TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      ProjectCommandOptions options)
      throws IOException, InterruptedException {
    // Create an ActionGraph that only contains targets that can be represented as IDE
    // configuration files.
    ActionGraph actionGraph = targetGraphTransformer.apply(targetGraphAndTargets.getTargetGraph());

    try (ExecutionContext executionContext = createExecutionContext()) {
      Project project = new Project(
          new SourcePathResolver(new BuildRuleResolver(actionGraph.getNodes())),
          FluentIterable
              .from(actionGraph.getNodes())
              .filter(ProjectConfig.class)
              .toSet(),
          actionGraph,
          options.getBasePathToAliasMap(),
          options.getJavaPackageFinder(),
          executionContext,
          new FilesystemBackedBuildFileTree(
              getProjectFilesystem(),
              new ParserConfig(options.getBuckConfig()).getBuildFileName()),
          getProjectFilesystem(),
          options.getPathToDefaultAndroidManifest(),
          options.getPathToPostProcessScript(),
          new PythonBuckConfig(options.getBuckConfig()).getPythonInterpreter(),
          getObjectMapper(),
          options.isAndroidAutoGenerateDisabled());

      File tempDir = Files.createTempDir();
      File tempFile = new File(tempDir, "project.json");
      int exitCode;
      try {
        exitCode = project.createIntellijProject(
            tempFile,
            executionContext.getProcessExecutor(),
            !passedInTargetsSet.isEmpty(),
            console.getStdOut(),
            console.getStdErr());
        if (exitCode != 0) {
          return exitCode;
        }

        List<String> additionalInitialTargets = ImmutableList.of();
        if (options.shouldProcessAnnotations()) {
          try {
            additionalInitialTargets = getAnnotationProcessingTargets(
                projectGraph,
                passedInTargetsSet);
          } catch (BuildTargetException | BuildFileParseException e) {
            throw new HumanReadableException(e);
          }
        }

        // Build initial targets.
        if (options.hasInitialTargets() || !additionalInitialTargets.isEmpty()) {
          BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
          BuildCommandOptions buildOptions =
              options.createBuildCommandOptionsWithInitialTargets(additionalInitialTargets);


          exitCode = buildCommand.runCommandWithOptions(buildOptions);
          if (exitCode != 0) {
            return exitCode;
          }
        }
      } finally {
        // Either leave project.json around for debugging or delete it on exit.
        if (console.getVerbosity().shouldPrintOutput()) {
          getStdErr().printf("project.json was written to %s", tempFile.getAbsolutePath());
        } else {
          tempFile.delete();
          tempDir.delete();
        }
      }

      if (passedInTargetsSet.isEmpty()) {
        String greenStar = console.getAnsi().asHighlightedSuccessText(" * ");
        getStdErr().printf(
            console.getAnsi().asHighlightedSuccessText("=== Did you know ===") + "\n" +
            greenStar + "You can run `buck project <target>` to generate a minimal project " +
            "just for that target.\n" +
            greenStar + "This will make your IDE faster when working on large projects.\n" +
            greenStar + "See buck project --help for more info.\n" +
            console.getAnsi().asHighlightedSuccessText(
                "--=* Knowing is half the battle!") + "\n");
      }

      return 0;
    }
  }

  ImmutableList<String> getAnnotationProcessingTargets(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> buildTargets;
    if (!passedInTargetsSet.isEmpty()) {
      buildTargets = passedInTargetsSet;
    } else {
      buildTargets = getRootsFromPredicate(
          projectGraph,
          ANNOTATION_PREDICATE);
    }
    return FluentIterable
        .from(buildTargets)
        .transform(Functions.toStringFunction())
        .toList();
  }

  /**
   * Run xcode specific project generation actions.
   */
  int runXcodeProjectGenerator(
      TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      ProjectCommandOptions options)
      throws IOException, InterruptedException {
    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (options.getReadOnly()) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }
    if (options.isWithTests()) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_TESTS);
    }

    boolean combinedProject = options.getCombinedProject();
    ImmutableSet<BuildTarget> targets;
    if (passedInTargetsSet.isEmpty()) {
      targets = FluentIterable
          .from(targetGraphAndTargets.getProjectRoots())
          .transform(HasBuildTarget.TO_TARGET)
          .toSet();
    } else {
      targets = passedInTargetsSet;
    }
    if (combinedProject) {
      optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS);
    } else {
      optionsBuilder.addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS);
    }
    LOG.debug("Generating workspace for config targets %s", targets);
    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    ImmutableSet<TargetNode<?>> testTargetNodes = targetGraphAndTargets.getAssociatedTests();
    ImmutableSet<TargetNode<AppleTestDescription.Arg>> groupableTests =
      options.getCombineTestBundles()
          ? AppleBuildRules.filterGroupableTests(testTargetNodes)
          : ImmutableSet.<TargetNode<AppleTestDescription.Arg>>of();
    ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder = ImmutableSet.builder();
    for (BuildTarget workspaceTarget : targets) {
      TargetNode<?> workspaceNode = Preconditions.checkNotNull(
          targetGraphAndTargets.getTargetGraph().get(workspaceTarget));
      if (workspaceNode.getType() != XcodeWorkspaceConfigDescription.TYPE) {
        throw new HumanReadableException(
            "%s must be a xcode_workspace_config",
            workspaceTarget);
      }
      WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
          getProjectFilesystem(),
          targetGraphAndTargets.getTargetGraph(),
          castToXcodeWorkspaceTargetNode(workspaceNode),
          optionsBuilder.build(),
          combinedProject,
          new ParserConfig(options.getBuckConfig()).getBuildFileName());
      generator.setGroupableTests(groupableTests);
      generator.generateWorkspaceAndDependentProjects(projectGenerators);
      ImmutableSet<BuildTarget> requiredBuildTargetsForWorkspace =
          generator.getRequiredBuildTargets();
      LOG.debug(
          "Required build targets for workspace %s: %s",
          workspaceTarget,
          requiredBuildTargetsForWorkspace);
      requiredBuildTargetsBuilder.addAll(requiredBuildTargetsForWorkspace);
    }

    int exitCode = 0;
    ImmutableSet<BuildTarget> requiredBuildTargets = requiredBuildTargetsBuilder.build();
    if (!requiredBuildTargets.isEmpty()) {
      BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
      BuildCommandOptions buildCommandOptions = new BuildCommandOptions(options.getBuckConfig());
      buildCommandOptions.setArguments(
          FluentIterable.from(requiredBuildTargets)
              .transform(Functions.toStringFunction())
              .toList());
      exitCode = buildCommand.runCommandWithOptions(buildCommandOptions);
    }
    return exitCode;
  }

  @SuppressWarnings(value = "unchecked")
  private static TargetNode<XcodeWorkspaceConfigDescription.Arg> castToXcodeWorkspaceTargetNode(
      TargetNode<?> targetNode) {
    Preconditions.checkArgument(targetNode.getType() == XcodeWorkspaceConfigDescription.TYPE);
    return (TargetNode<XcodeWorkspaceConfigDescription.Arg>) targetNode;
  }

  private void checkForAndKillXcodeIfRunning(boolean enablePrompt)
      throws InterruptedException, IOException {
    Optional<ProcessManager> processManager = getProcessManager();
    if (!processManager.isPresent()) {
      LOG.warn("Could not check if Xcode is running (no process manager)");
      return;
    }

    if (!processManager.get().isProcessRunning(XCODE_PROCESS_NAME)) {
      LOG.debug("Xcode is not running.");
      return;
    }

    if (enablePrompt && canPrompt()) {
      if (prompt(
              "Xcode is currently running. Buck will modify files Xcode currently has " +
              "open, which can cause it to become unstable.\n\n" +
              "Kill Xcode and continue?")) {
        processManager.get().killProcess(XCODE_PROCESS_NAME);
      } else {
        console.getStdOut().println(
            console.getAnsi().asWarningText(
                "Xcode is running. Generated projects might be lost or corrupted if Xcode " +
                "currently has them open."));
      }
      console.getStdOut().format(
          "To disable this prompt in the future, add the following to %s: \n\n" +
              "[project]\n" +
              "  ide_prompt = false\n\n",
          getProjectFilesystem()
              .getRootPath()
              .resolve(BuckConfig.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME)
              .toAbsolutePath());
    } else {
      LOG.debug(
          "Xcode is running, but cannot prompt to kill it (enabled %s, can prompt %s)",
          enablePrompt, canPrompt());
    }
  }

  private boolean canPrompt() {
    return System.console() != null;
  }

  private boolean prompt(String prompt) throws IOException {
    Preconditions.checkState(canPrompt());

    LOG.debug("Displaying prompt %s..", prompt);
    console.getStdOut().print(console.getAnsi().asWarningText(prompt + " [Y/n] "));

    Optional<String> result;
    try (InputStreamReader stdinReader = new InputStreamReader(System.in, Charsets.UTF_8);
         BufferedReader bufferedStdinReader = new BufferedReader(stdinReader)) {
      result = Optional.fromNullable(bufferedStdinReader.readLine());
    }
    LOG.debug("Result of prompt: [%s]", result);
    return result.isPresent() &&
      (result.get().isEmpty() || result.get().toLowerCase(Locale.US).startsWith("y"));
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> getRootsFromPredicate(
      TargetGraph projectGraph,
      Predicate<TargetNode<?>> rootsPredicate) {
    return FluentIterable
        .from(projectGraph.getNodes())
        .filter(rootsPredicate)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();
  }

  private static Iterable<? extends TargetNodeSpec> getTargetNodeSpecsForIde(
      ProjectCommandOptions.Ide ide,
      Collection<BuildTarget> passedInBuildTargets,
      ImmutableSet<Path> ignoreDirs
  ) {
    if (ide == ProjectCommandOptions.Ide.XCODE &&
        !passedInBuildTargets.isEmpty()) {
      return Iterables.transform(
          passedInBuildTargets,
          BuildTargetSpec.TO_BUILD_TARGET_SPEC);
    } else {
      return ImmutableList.of(
          new TargetNodePredicateSpec(
              Predicates.<TargetNode<?>>alwaysTrue(),
              ignoreDirs));
    }
  }

  private static TargetGraphAndTargets createTargetGraph(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> graphRoots,
      ProjectGraphParser projectGraphParser,
      AssociatedTargetNodePredicate associatedProjectPredicate,
      boolean isWithTests,
      ProjectCommandOptions.Ide ide,
      ImmutableSet<Path> ignoreDirs
  )
    throws IOException, InterruptedException {

    TargetGraph resultProjectGraph;
    ImmutableSet<BuildTarget> explicitTestTargets;

    if (isWithTests) {
        explicitTestTargets = TargetGraphAndTargets.getExplicitTestTargets(
            graphRoots,
            projectGraph);
        resultProjectGraph =
            projectGraphParser.buildTargetGraphForTargetNodeSpecs(
                getTargetNodeSpecsForIde(
                    ide,
                    Sets.union(graphRoots, explicitTestTargets),
                    ignoreDirs));
    } else {
      resultProjectGraph = projectGraph;
      explicitTestTargets = ImmutableSet.of();
    }

    return TargetGraphAndTargets.create(
        graphRoots,
        resultProjectGraph,
        associatedProjectPredicate,
        isWithTests,
        explicitTestTargets);
  }

  @Override
  String getUsageIntro() {
    return "generates project configuration files for an IDE";
  }

}
