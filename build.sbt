import scalariform.formatter.preferences._

lazy val akkaVersion = "2.5.8"
lazy val akkaHttpVersion = "10.0.11"
lazy val scalaTestVersion = "3.0.3"
lazy val sprayJsonVersion = "1.3.4"
lazy val corsVersion = "0.2.2"
 
lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      name := "akka-http-client",
      description := "Simple Akka HTTP Client DSL for Scala.",
      organization := "com.zxtx",
      scalaVersion := "2.12.4",
      version      := "0.1.0"
    )),
    name := "akka-persistence-elasticsearch",
    libraryDependencies ++= Seq(
      "com.typesafe.akka"                  %% "akka-actor"              % akkaVersion,
      "com.typesafe.akka"                  %% "akka-remote"             % akkaVersion,
      "com.typesafe.akka"                  %% "akka-cluster"            % akkaVersion,
      "com.typesafe.akka"                  %% "akka-cluster-tools"      % akkaVersion,
      "com.typesafe.akka"                  %% "akka-cluster-sharding"   % akkaVersion,
      "com.typesafe.akka"                  %% "akka-distributed-data"   % akkaVersion,
      "com.typesafe.akka"                  %% "akka-persistence"        % akkaVersion,
      "com.typesafe.akka"                  %% "akka-persistence-query"  % akkaVersion,
      "com.typesafe.akka"                  %% "akka-http"               % akkaHttpVersion,
      "com.typesafe.akka"                  %% "akka-multi-node-testkit" % akkaVersion,
      "com.typesafe.akka"                  %% "akka-http-spray-json"    % akkaHttpVersion,
      "com.pauldijou"                      %% "jwt-core"                % "0.16.0",      
      "com.eclipsesource.j2v8"             %  "j2v8_linux_x86_64"       % "4.8.0",
      "org.gnieh"                          %%  f"diffson-spray-json"    % "2.2.5",
      "com.softwaremill.akka-http-session" %% "core"                    % "0.5.4",
      "com.roundeights"                    %% "hasher"                  % "1.2.0",
      "org.slf4j"                          % "slf4j-api"                % "1.7.25",
      "org.scala-sbt.ipcsocket"            % "ipcsocket"                % "1.0.0",
      "org.slf4j"                          % "slf4j-simple"             % "1.7.25" % "test",
      
      "ch.megard"                          %% "akka-http-cors"          % corsVersion,
      "io.spray"                           %% "spray-json"              % sprayJsonVersion,
      "org.iq80.leveldb"                   % "leveldb"                  % "0.7",
      "org.fusesource.leveldbjni"          % "leveldbjni-all"           % "1.8",
      
      "org.scalatest"                      %% "scalatest"               % scalaTestVersion   % Test,
      "com.typesafe.akka"                  %% "akka-persistence-tck"    % akkaVersion        % Test,
      "com.typesafe.akka"                  %% "akka-http-testkit"       % akkaHttpVersion    % Test,
      "com.typesafe.akka"                  %% "akka-testkit"            % akkaVersion        % Test,
      "com.typesafe.akka"                  %% "akka-stream-testkit"     % akkaVersion        % Test,
      "junit"                              %  "junit"                   % "4.8.1"            % Test
    )
  )

fork in run := true

scalariformPreferences := scalariformPreferences.value
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentConstructorArguments, true)
    .setPreference(DanglingCloseParenthesis, Preserve)
