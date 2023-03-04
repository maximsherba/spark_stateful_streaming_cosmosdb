ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.10"

lazy val root = (project in file("."))
  .settings(
    name := "spark-stateful-streaming"
  )

val sparkVersion = "3.3.1"//"3.0.1" //3.2.1
val logVersion = "2.17.1"
val circeVersion = "0.14.3"
val cassandraConnectorVersion = "3.0.0"

resolvers ++= Seq(
  //"bintray-spark-packages" at "https://dl.bintray.com/spark-packages/maven",
  "Typesafe Simple Repository" at "https://repo.typesafe.com/typesafe/simple/maven-releases",
  "MavenRepository" at "https://mvnrepository.com",
  "Azure CosmosDB Cassandra Library" at "https://dl.bintray.com/azure/maven"
)

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-streaming" % sparkVersion,
  "com.microsoft.azure" % "azure-eventhubs-spark_2.12" % "2.3.22",
  "com.datastax.spark" %% "spark-cassandra-connector" % cassandraConnectorVersion,
  "com.github.jnr" % "jnr-posix" % "3.1.5",
)

//circe
libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)
libraryDependencies += "com.typesafe.play" %% "play-json" % "2.9.4"

//jsoniter
resolvers += "Jsoniter Bintray Repo" at "https://dl.bintray.com/plokhotnyuk/maven"
libraryDependencies ++= Seq(
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % "2.20.6",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.20.6",
)

//databricks delta-tables
libraryDependencies ++= Seq(
  "io.delta" %% "delta-core" % "2.0.2",
)
resolvers ++= Seq(
  "Delta Lake IO" at "https://repo.delta.io",
)

//azure data lake
libraryDependencies ++= Seq(
  "org.apache.hadoop" % "hadoop-azure" % "3.3.1",
)
