# action-maven-publish
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=jskov_action-maven-publish&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jskov_action-maven-publish)

This Action will [sign and publish](https://central.sonatype.org/publish/publish-portal-api/) Maven artifacts to [MavenCentral](https://central.sonatype.org/) for security aware (and/or paranoid) developers.

## Simple(r) and Transparent

The Action does the same as the publishing plugins of Maven and Gradle (or near enough).
But it does so in less than 1kLOC self-contained java that any (java) programmer should be able to verify the logic of.

When using a plugin in Maven or Gradle, all other activated plugins (and potentially any dependency jar used for compilation/testing) will be able to exfiltrate your GPG signing key and MavenCentral credentials.

Depending on your level of paranoia this may be OK. Or it could be a problem.

For me it is a problem, hence this Action.

### Runtime

The Action runtime is built just using `javac` without any dependencies (just the classes in ['src/main/java'](./src/main/java)).
This is done as the Action is run - see [action.yaml](./action.yaml).

There are no binaries or third party dependencies used for running this Action. Just the Java SE library.

### Releases / Tags

I will be tagging this repository to have named base lines and to make it simpler to make release notes.

You should however **not** use a tag name (or worse, a branch name) when referencing this Action.
Otherwise you run the theoretical risk of the tag being moved in bad faith (or worse - and more likely - suffer bugs in new commits due to my incompetence).

You *should* be using the Git hash from a release (see `Code Review` section below).

I do not foresee many future releases.

### Code Review

So if you cannot trust your secrets to Maven or Gradle (and whatever dependencies you drag into your build), why should you trust this Action?

Well, you should not!

You would do well to fork this repository and review the code. And then use the Action from your forked repository!
Basically the [official](https://docs.github.com/en/actions/security-guides/security-hardening-for-github-actions#using-third-party-actions) recommendations but with extra paranoia :)

According to [SonarCloud](https://sonarcloud.io/component_measures?metric=lines_to_cover&selected=jskov_action-maven-publish%3Asrc%2Fmain%2Fjava%2Fdk%2Fmada%2Faction&id=jskov_action-maven-publish) there are <600 code lines of java (~1800 textual lines).

If you have written enough code to publish anything on MavenCentral, it should not be hard to review.

Pay attention to handling of environment variables (where your secrets will be) and what gets printed to the console.  
And verify that no other external communication/execution happens that could leak the secrets.

Here is a guided tour:

* Start in [action.yaml](./action.yaml) and verify that only the ['src/main/java'](./src/main/java) code is compiled and run.  
* The java implementation is entered via the main class [ActionNexusPublisher](./src/main/java/dk/mada/action/ActionNexusPublisher.java).
* It parses the arguments (environment variables) via [ActionArguments](./src/main/java/dk/mada/action/ActionArguments.java).  
  Note that the types containing secrets (GpgCertificate and PortalCredentials) override toString() to avoid accidental exposure.
* The logger is configured from [logging.properties](./src/main/resources/logging.properties).
* The [GpgSigner](./src/main/java/dk/mada/action/GpgSigner.java) is created in a try-with-resources to ensure that it will eventually delete the created temporary directory GNUPGHOME.
* Instances are created of [BundleCollector](./src/main/java/dk/mada/action/BundleCollector.java), [PortalProxy](./src/main/java/dk/mada/action/portal/PortalProxy.java), and [BundlePublisher](./src/main/java/dk/mada/action/BundlePublisher.java).
* Now the GPG certificate is loaded into GNUPGHOME using the external `gpg` command (this must be provided by the OS executing your workflow).  
    Pay attention to where the GPG certifiate key and secret are accessed (hint, the secret is only used later when signing files - where it is passed via stdin to the `gpg` process).  
    For this, you want to visit [ExternalCmdRunner](./src/main/java/dk/mada/action/util/ExternalCmdRunner.java) and verify that it does not squirrel away (or spills by accident) your secrets.  
* [BundleCollector](./src/main/java/dk/mada/action/BundleCollector.java) finds the files to be published.  
    This asserts that you have provided `.sha1` and `.md5` files for each artifact.  
    If so the artifacts are signed with via [GpgSigner](./src/main/java/dk/mada/action/GpgSigner.java).  
    It packages artifacts, checksum files, and signature files into bundles (which are jar-files).  
    The layout in the bundle archives needs to match the artifact GAV, so [XmlExtractor](./src/main/java/dk/mada/action/util/XmlExtractor.java) is used to extract that information from each POM file.
* [BundlePublisher](./src/main/java/dk/mada/action/BundlePublisher.java) can now upload the bundles to Maven Central.  
    The [PortalProxy](./src/main/java/dk/mada/action/portal/PortalProxy.java) is used for all communication with the Publich Portal API.  
    Your PortalCredentials are used to construct the Authorization header used in these calls (check that it is not used for anything else - and not logged).
* [BundlePublisher](./src/main/java/dk/mada/action/BundlePublisher.java) keeps polling the status resource until the repositories settle.  
    The status is parsed from JSON using [JsonExtractor](./src/main/java/dk/mada/action/util/JsonExtractor.java).  
    Status will be printed to the console while the loop runs.  
*  Depending on your configured target action, the repositories are dropped, kept (for manual cleanup/publishing), or attempted published.
* On completion the Action will return success or failure depending on the final states of the repositories.

Did you find something weird or broken? Please let me know (privately if it could affect the security).


### Testing

The repository contains both `gradlew` and [build file](./build.gradle)+[toml file](./gradle/libs.versions.toml) with dependencies to junit and assertj.

These are **only** used for development. They are not used in the execution of the Action.

There are unit-tests which can be executed without additional context.  
These use a [test-only private GPG key](./src/test/resources) generated by this [script](./src/test/scripts/create-test-signing-key.sh).
To update the GPG key, run the script from the repository root.

And there is a single integration-test which uploads a bundle to Central Portal (this needs credentials, so can only be run manually by someone providing said credentials).

### Examples of Use

I use this Action with my own (Gradle-based) repositories:

 * https://github.com/jskov/mada-style-gradle
 * https://github.com/jskov/openapi-jaxrs-client

If you have an example of a Maven-based repository using this Action (and would not mind having it mentioned here), please give me a reference.

### FAQish

* *Why did you not write this Action in javascript - it is more conventional for Actions?*  
  I do not have enough experience with the plain javascript language to do that (efficiently, anyway).  
  And including 27 NPMs to solve the problem would defeat the purpose of this Action.  

  The target audience of this Action consists of java programmers; they should be able to easily review the code.

* *Why did you not write this Action in shell script - it is more conventional for Actions?*  
  Same as above, really.  
  I do believe it could be done much simpler in shell script by someone with enough experience there.  
  But while shell script is a more simple and common "language" (than javascript/java), I believe there are still a lot
  of java developers that would prefer to review java code.

* *Why is this action not published on the Marketplace? Seems fishy!*  
  Yeah, well, I think the same (fishy) of the [GitHub Marketplace Developer Agreement](https://docs.github.com/en/site-policy/github-terms/github-marketplace-developer-agreement).
  
  As far as I can tell (and I am a programmer, not a lawyer, mind you), those 35kB of legalese are there to protect the Marketplace from idiots.  
  But being in the Marketplace adds nothing to an action (that I can tell), except being in the Marketplace's searchable index. Where it will drown anyway.

  Since this action is primarily here because of (to the benefit of) me, I have already reached my target audience.  
  So hard pass on the extraneous legalese.

* *Can my dependencies in Maven/Gradle really read my secrets?*  
  Sure, any class that is loaded/instantiated can do anything with the environment provided.  

  Can you be sure what classes are loaded during a Maven/Gradle invocation?  
  You may trust the two projects behind Maven and Gradle, but do you trust all the dependencies they use during a run?  
  
  As a mitigation, you can publish with Maven/Gradle in a separate step that only invokes the publishing tasks.  
  That is, only giving your secrets to this step, not the step(s) building/testing your code.  

  This way you can exclude risk from all your project's (transitive) build and test dependencies, plus those
  from Maven/Gradle plugins that are not activated by publishing (if any?).

* *How should I review the code, then?*  
  See [code review](#code-review)!

* *Are you really this paranoid?*  
  When it suits me.

* *So you trust the JDK? GitHub?*  
  Well, your paranoia has to rest on a bedrock of *something*. Otherwise you will drown :)  

  Yeah, I trust the JDK. And the Temurin build of it. And the `actions/setup-java` action, GitHub in general, and the Ubuntu runner they provide.  
  Or I would have stayed in my cave.

  But (in my optics, anyway) there is a large gap between the above and the sum of transitive dependencies included when developing an average application.  
  In particular if you do not have full control how dependencies are added or changed over time.

### Open Source vs Expectancy of Work

This project is Open Source and I am happy for you to use the Action, report bugs and suggest changes.

But while the source code is free, it does not come bundled with promises or guarantees of free work.

I will try to fix reported bugs (if I agree with them), but will commit to no time tables.

If you are itching to make some changes (to this repository), please open an issue first, so we can discuss.  
I do not want you to waste your time!

If this is not agreeable, you are more than welcome to fork the project and do your own thing.

Open Source, Yay!


## Using the Action

<!-- action-docs-description -->

### Description

Publishing Maven artifacts to Maven Central.

<!-- action-docs-description -->

<!-- action-docs-inputs -->

### Inputs

| parameter | description | required | default |
| --- | --- | --- | --- |
| search_directory   | The directory to search for POM files | `true` | |
| companion_suffixes | The companion files to include with found POM files, comma-separated | `false` | ".jar, .module, -javadoc.jar, -sources.jar" |
| signing_key        | GPG private signing key | `true` | |
| signing_key_secret | GPG signing key secret | `true` | |
| portal_username    | The Portal login name | `true` | |
| portal_token       | The Portal token | `true` | |
| target_action      | The action to take for bundles after upload (drop/keep/promote_or_keep) | `false` | keep |
| log_level          | Log level (for JUL framework) (info/fine/finest) | `false` | info |
| initial_pause      | The per-bundle initial pause (in seconds) before polling for completion state changes | `false` | 45 |
| loop_pause         | The per-bundle loop pause (in seconds) between polling for completion state changes | `false` | 15 |

<!-- action-docs-inputs -->


<!-- action-docs-outputs -->

<!-- action-docs-outputs -->

<!-- action-docs-runs -->

### Runs

This is a [composite](https://docs.github.com/en/actions/creating-actions/creating-a-composite-action) Action, publishing artifacts to MavenCentral using the [Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/).

The java code in ['src/main/java'](./src/main/java) is compiled and started.

The `search_directory` is searched for '*.pom' files.

For each pom-file a bundle is created. The bundle contains the pom-file and companion files matching the pom-file's basename appended each of the strings in `companion_suffixes`.

The bundle files are packaged into a jar-file which is signed using GPG (with `signing_key`/`signing_key_secret`).

Then the bundle jar-files are uploaded to the Portal Publisher API using `portal_username`/`portal_token`.

After all bundles are uploaded, a loop is entered waiting for all the bundles to be processed on Portal Central.
The loop is entered after an initial delay, and there is a delay after each loop round. Both configured in seconds, and multiplied by the number of bundles.
Reducing the loop delay does not increase processing speed - it just results in more polling.

Finally, all the uploaded bundles are dropped/kept/promoted according to `target_action`.
Note that if any of the bundles fail validation, 'promote' will fall back to 'keep' (hence 'promote_or_keep').

See [Central Portal instructions](https://central.sonatype.org/register/central-portal/) for how to prepare the necessary [GPG](https://central.sonatype.org/publish/requirements/gpg/) and [Token](https://central.sonatype.org/publish/generate-portal-token/) arguments.

<!-- action-docs-runs -->
