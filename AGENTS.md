# Instructions for AI Agents

## Code format

You never need to worry about code formatting at all. Simply run `mvn formatter:format` before committing changes to make sure the new code follows this project's code formatting rules. Make sure not to run `mvn formatter:format` separately before other Maven commands, to avoid concurrency issues.

All files must include the proper license header. When you add a new file, make sure to include the proper license header by running the `mvn license:update-file-header` command before committing (or even before trying the build and test, since the build will fail if a file doesn't include the proper license header).

All public methods must have proper Javadoc. Check the output of Maven to identify issues with Javadoc and fix these issues.

## Build

The project uses Maven to build. A full build is performed with `mvn verify site` (or `mvn clean verify site` when applicable).

@codex, please don't try to use `mvnw` (Maven Wrapper). Maven is already installed and runs perfectly well.

## Test

Whenever required, when you add code or when you modify code that is not covered with unit tests, add the corresponding unit tests. All tests must pass with `mvn test`. Don't use the `-q` (silent) option, as you want to see the result of successful tests. Tests are run with the Maven surefire plugin and results are stored in the ./target/surefire-reports directory.

## Code quality reports

Code quality checks are performed during the build with `mvn verify` (checkstyle, pmd, and spotbugs). Always build the project with `mvn verify` and fix any problem reported in ./target/checkstyle-result.xml, ./target/pmd.xml, and ./target/spotbugsXml.xml before committing and submitting your code!

## Documentation

Any change that affects the end user of this library must be properly documented in the site documentation under `src/site/markdown/`. In particular, `src/site/markdown/cli.md` is the single source of truth for the command-line client: new CLI options go in its options table, plus one short line in the `--help` output.

README.md is deliberately terse — a quick start, a few representative examples, and links to the full documentation. Do NOT catalog every option or minor feature there; update README.md only when a change invalidates or alters what it already shows (quick-start snippets, examples, upgrade notes).

