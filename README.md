Maven repository importer for Neo4j
============================================================

This little nifty tool will allow you to import your local Maven repository information into a Neo4j graph, in particular dependencies between artifacts. You can then
take this graph and put it into a Neo4j server, and perform Cypher queries on it. Or whatever else awesome you want to do.

If you pass a list of directories in with the `-gits` argument, it will add git repositories for the repos and establish graph edges from each repo to the artifact(s)
defined in that repo.

WARNING: if you open webadmin and use the graph visualization, whatever you do don't click on "junit". Dependency explosion ahead. You have been warned.

Usage
-----
* Clone and build using Maven
* Run the Main class from command line. The output directory will be created as necessary. Example:
```
java -jar neomvn-1.0-SNAPSHOT.jar -repoPath /Users/me/.m2/repository -gits ~/dev -destPath ../maven-deps
```

* This will import and index your local Maven repository into a Neo4j graph database created under "../maven-deps" directory, from where the tool was invoked.

* Copy database into your own application or server, and perform awesome Cypher queries against it

Model
-----
This is the model currently used:
There are four known labels for nodes: Repo, Version, Artifact, Group
* Each groupId gets a corresponding node with property "groupId"
* Each artifactId gets a corresponding node, and a HAS_ARTIFACTID to its groupId, and properties "groupId" and "artifactId"
* Each version gets a corresponding node, and a HAS_VERSION to its artifactId, and properties "groupId","artifactId", "version" and "name"
* Each dependency is modeled as a HAS_DEPENDENCY from the depending version/artifactId/groupId to the depended on version/artifactId/groupId. Scope and optional as properties
* Each repo is a node with an IN_REPO edge to each version that is defined in that repo.
* There are four indices: repos, groups, artifacts, and versions. Search by "repo", "groupId", "artifactId", and "version" respectively, to find starting points for queries

Example queries
---------------
Here's a few sample queries you can try out:

Find all transitive dependencies of all artifacts Neo Technology has ever published:
```
start group=node:groups(groupId='org.neo4j')
match group-[:HAS_ARTIFACT]->artifact-[:HAS_VERSION]->version-[:HAS_DEPENDENCY*..5]->dependency
return distinct dependency;
```

Find all projects where any version depends on any artifact that Neo Technology has published:
```
start group=node:groups(groupId='org.neo4j')
match group-[:HAS_ARTIFACT]->artifact-[:HAS_VERSION]->version<-[:HAS_DEPENDENCY]-dependent
where left(dependent.groupId,9)<>group.groupId
return distinct dependent.artifactId, dependent.groupId;
```

Find which version of JUnit is the most popular:
```
start group=node:groups(groupId='junit')
match group-[:HAS_ARTIFACT]->artifact-[:HAS_VERSION]->version<-[:HAS_DEPENDENCY]-dependent
return version.version, count(dependent) as depCount
order by depCount desc
```

For the latest version of all artifacts, what version of JUnit do they use:
```
start group=node:groups('groupId:*')
match group-[:HAS_ARTIFACT]->artifact-[:HAS_VERSION]->version
with artifact, version
order by version.version desc
with artifact, head(collect(version)) as latestVersion
match latestVersion-[:HAS_DEPENDENCY]->dependency
where dependency.artifactId='junit'
return artifact.artifactId, latestVersion.version, dependency.version
order by dependency.version
```

What projects' latest artifacts transitively depend on an outdated version of Neo4j:
```
start group=node:groups('groupId:*')
match group-[:HAS_ARTIFACT]->artifact-[:HAS_VERSION]->version
where group.groupId<>'org.neo4j'
with artifact,version
order by version.version desc
with artifact, head(collect(version)) as latestVersion
match latestVersion-[:HAS_DEPENDENCY*..5]->dep
where dep.groupId='org.neo4j'
return distinct artifact.artifactId, latestVersion.groupId, latestVersion.version, dep.artifactId, dep.version
order by dep.version
```

License
-------
This library is made available under the Apache Software License 2.0.
