package dev.weihantu.releaseguard.cli;

import dev.weihantu.releaseguard.api.validation.ValidationError;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ReleaseGuardCli {
  private final ManifestLoader loader;
  private final PlanProcessor processor;

  public ReleaseGuardCli() {
    this(new ManifestLoader(), new PlanProcessor());
  }

  ReleaseGuardCli(ManifestLoader loader, PlanProcessor processor) {
    this.loader = loader;
    this.processor = processor;
  }

  public static void main(String[] args) {
    int exitCode =
        new ReleaseGuardCli()
            .run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
    if (exitCode != ExitCode.SUCCESS) {
      System.exit(exitCode);
    }
  }

  int run(String[] args, PrintWriter out, PrintWriter err) {
    try {
      Command command = Command.parse(args);
      if (command.help()) {
        out.print(usage());
        out.flush();
        return ExitCode.SUCCESS;
      }
      ManifestLoader.LoadedPlan loaded = loader.load(command.file(), command.policyFiles());
      PlanProcessor.Evaluation evaluation = processor.validate(loaded);
      if (command.action() == Action.VALIDATE) {
        writeValidation(command, evaluation, out);
      } else if (!evaluation.result().isValid()) {
        writeValidation(command, evaluation, out);
      } else {
        PlanProcessor.RenderedPlan rendered =
            processor.render(loaded, command.uid(), command.generation());
        writeRendered(command, rendered, out, err);
      }
      return evaluation.result().isValid() ? ExitCode.SUCCESS : ExitCode.VALIDATION;
    } catch (CliException exception) {
      err.println("error: " + exception.getMessage());
      if (exception.exitCode() == ExitCode.USAGE) {
        err.print(usage());
      }
      err.flush();
      return exception.exitCode();
    }
  }

  private void writeValidation(
      Command command, PlanProcessor.Evaluation evaluation, PrintWriter out) throws CliException {
    if (command.output() == Output.JSON) {
      out.print(
          processor.json(
              new ValidationOutput(
                  evaluation.result().isValid(),
                  evaluation.plan(),
                  evaluation.acceptedPolicyCount(),
                  evaluation.result().errors())));
    } else if (evaluation.result().isValid()) {
      out.printf(
          "valid: %s (%d accepted policies)%n",
          evaluation.plan(), evaluation.acceptedPolicyCount());
    } else {
      out.printf("invalid: %s%n", evaluation.plan());
      for (ValidationError error : evaluation.result().errors()) {
        out.printf("[%s] %s: %s%n", error.requirement(), error.path(), error.message());
      }
    }
    out.flush();
  }

  private void writeRendered(
      Command command, PlanProcessor.RenderedPlan rendered, PrintWriter out, PrintWriter err)
      throws CliException {
    if (command.output() == Output.JSON) {
      out.print(processor.json(rendered.resources()));
    } else {
      out.print(processor.yaml(rendered.resources()));
    }
    out.flush();
    err.printf(
        "rendered %d resources with uid=%s generation=%d; no cluster changes were made%n",
        rendered.resources().size(), rendered.uid(), rendered.generation());
    if (rendered.syntheticUid()) {
      err.println(
          "warning: owner references use a deterministic offline UID; replace it with the live ReleasePlan UID before applying output");
    }
    err.flush();
  }

  private static String usage() {
    return """
        Usage:
          releaseguard plan validate -f PLAN [-p POLICY ...] [-o text|json]
          releaseguard plan render   -f PLAN [-p POLICY ...] [-o yaml|json]
                                     [--uid UID] [--generation NUMBER]

        MetricPolicy YAML files beside PLAN are discovered automatically. Use -p to add files
        from other directories. Validation uses release-environment rules and never mutates a cluster.
        """;
  }

  private enum Action {
    VALIDATE,
    RENDER
  }

  private enum Output {
    TEXT,
    YAML,
    JSON
  }

  private record ValidationOutput(
      boolean valid, String plan, int acceptedPolicyCount, List<ValidationError> errors) {}

  private record Command(
      Action action,
      Path file,
      List<Path> policyFiles,
      Output output,
      String uid,
      Long generation,
      boolean help) {
    private static Command parse(String[] args) throws CliException {
      if (args.length == 0 || isHelp(args[0])) {
        return new Command(Action.VALIDATE, null, List.of(), Output.TEXT, null, null, true);
      }
      if (args.length < 2 || !"plan".equals(args[0])) {
        throw usageError("expected 'plan validate' or 'plan render'");
      }
      Action action =
          switch (args[1]) {
            case "validate" -> Action.VALIDATE;
            case "render" -> Action.RENDER;
            default -> throw usageError("unknown plan command: " + args[1]);
          };
      Path file = null;
      List<Path> policies = new ArrayList<>();
      Output output = action == Action.RENDER ? Output.YAML : Output.TEXT;
      String uid = null;
      Long generation = null;
      for (int index = 2; index < args.length; index++) {
        String argument = args[index];
        switch (argument) {
          case "-h", "--help" -> {
            return new Command(action, file, List.copyOf(policies), output, uid, generation, true);
          }
          case "-f", "--file" -> file = Path.of(value(args, ++index, argument));
          case "-p", "--policy" -> policies.add(Path.of(value(args, ++index, argument)));
          case "-o", "--output" -> output = output(value(args, ++index, argument), action);
          case "--uid" -> uid = nonblank(value(args, ++index, argument), "UID");
          case "--generation" ->
              generation = positiveLong(value(args, ++index, argument), "generation");
          default -> throw usageError("unknown argument: " + argument);
        }
      }
      if (file == null) {
        throw usageError("-f/--file is required");
      }
      if (action == Action.VALIDATE && (uid != null || generation != null)) {
        throw usageError("--uid and --generation are valid only for plan render");
      }
      return new Command(action, file, List.copyOf(policies), output, uid, generation, false);
    }

    private static boolean isHelp(String argument) {
      return "-h".equals(argument) || "--help".equals(argument);
    }

    private static String value(String[] args, int index, String option) throws CliException {
      if (index >= args.length) {
        throw usageError(option + " requires a value");
      }
      return args[index];
    }

    private static String nonblank(String value, String name) throws CliException {
      if (value.isBlank()) {
        throw usageError(name + " must not be blank");
      }
      return value;
    }

    private static Long positiveLong(String value, String name) throws CliException {
      try {
        long parsed = Long.parseLong(value);
        if (parsed < 1) {
          throw usageError(name + " must be positive");
        }
        return parsed;
      } catch (NumberFormatException exception) {
        throw usageError(name + " must be a positive integer");
      }
    }

    private static Output output(String value, Action action) throws CliException {
      return switch (value) {
        case "json" -> Output.JSON;
        case "text" -> {
          if (action != Action.VALIDATE) {
            throw usageError("plan render output must be yaml or json");
          }
          yield Output.TEXT;
        }
        case "yaml" -> {
          if (action != Action.RENDER) {
            throw usageError("plan validate output must be text or json");
          }
          yield Output.YAML;
        }
        default -> throw usageError("unsupported output: " + value);
      };
    }

    private static CliException usageError(String message) {
      return new CliException(ExitCode.USAGE, message);
    }
  }
}
