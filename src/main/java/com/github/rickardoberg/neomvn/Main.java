package com.github.rickardoberg.neomvn;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.apache.commons.io.FileUtils;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

public class Main
{
    enum MavenNodeLabel implements Label {
        Artifact,
        Group,
        Version
    }

    public final static String ATTR_ID_ARTIFACT_ID = "artifactId";
    public final static String ATTR_ID_GROUP_ID = "groupId";
    public final static String ATTR_ID_VERSION_ID = "versionId";
    public final static String ATTR_ID_NAME = "name";
    /** full name (e.g. com.foo:foo-lib:1.3.0) */
    public final static String ATTR_ID_ARTIFACT_VERSION_NAME = "artifactName";
    public final static String ATTR_ID_POM_FILE_PATH = "pomFile";

    static class TransactionRoller {
        final GraphDatabaseService graphDatabaseService;

        Transaction tx;

        int counter;

        public TransactionRoller(GraphDatabaseService graphDatabaseService) {
            this.graphDatabaseService = graphDatabaseService;
            tx = graphDatabaseService.beginTx();
        }

        Transaction getTx() {
            return tx;
        }

        void commit() {
            ++counter;
            if (counter % 500 == 0) {
                logger.info("intermediate commit: pre");
                tx.success();
                tx.close();
                tx = graphDatabaseService.beginTx();
                logger.info("intermediate commit: post");
            }
        }

        void finish() {
            logger.info("final commit: pre");
            tx.success();
            tx.close();
            logger.info("final commit: post");
        }
    }

    private final GraphDatabaseService graphDatabaseService;
    private final File repository;
    private final static Logger logger = LoggerFactory.getLogger(Main.class);

    IndexDefinition groupIndex;
    IndexDefinition artifactIndex;
    IndexDefinition versionIndex;

    private final DynamicRelationshipType has_artifact = DynamicRelationshipType.withName("HAS_ARTIFACT");
    private final DynamicRelationshipType has_version = DynamicRelationshipType.withName("HAS_VERSION");
    private final DynamicRelationshipType has_dependency = DynamicRelationshipType.withName("HAS_DEPENDENCY");

    private ModelResolver modelResolver;

    private TransactionRoller txRoller;
    private List<String> failedPoms = new ArrayList<String>(  );

    public static void main( String[] args ) throws ParserConfigurationException, IOException, SAXException
    {
        if (args.length == 1)
            new Main(new File(args[0]));
        else
            new Main(new File("."));
    }

    IndexDefinition createIndexFor (GraphDatabaseService graphDb, MavenNodeLabel nodeType, String onAttr) {
        IndexDefinition indexDefinition;
        try ( Transaction tx = graphDb.beginTx() )
        {
            Schema schema = graphDb.schema();
            indexDefinition = schema.indexFor( DynamicLabel.label( nodeType.name() ) )
                .on( onAttr )
                .create();
            tx.success();
        }
        // wait
        try ( Transaction tx = graphDb.beginTx() )
        {
            Schema schema = graphDb.schema();
            schema.awaitIndexOnline( indexDefinition, 10, TimeUnit.SECONDS );
        }
        return indexDefinition;
    }

    public Main(File repository) throws ParserConfigurationException, IOException, SAXException
    {
        modelResolver = new ModelResolver( new RepositoryModelResolver(repository, "http://repo1.maven.org/maven2") );

        File dbPath = new File("neomvn");
        dbPath.mkdir();

        FileUtils.deleteDirectory( dbPath );

        graphDatabaseService = new GraphDatabaseFactory().newEmbeddedDatabase( dbPath);
        groupIndex = createIndexFor(graphDatabaseService, MavenNodeLabel.Group, ATTR_ID_GROUP_ID);
        artifactIndex = createIndexFor(graphDatabaseService, MavenNodeLabel.Artifact, ATTR_ID_ARTIFACT_ID);
        versionIndex = createIndexFor(graphDatabaseService, MavenNodeLabel.Version, ATTR_ID_VERSION_ID);

//        try (Transaction tx = graphDatabaseService.beginTx()) {
//            groups = graphDatabaseService.indx().forNodes("groups");
//            artifacts = graphDatabaseService.index().forNodes("artifacts");
//            versions = graphDatabaseService.index().forNodes("versions");

//            tx.success();
//        }

        txRoller = new TransactionRoller(graphDatabaseService);
        this.repository = repository;

        try
        {
            // Add versions
            logger.info( "Accumulating versions" );
            visitPoms(
                repository,
                model ->  {
                    String groupId = getGroupId( model );
                    String artifactId = model.getArtifactId();
                    String version = getVersion( model );
                    String name = model.getName();
                    if (name == null)
                        name = artifactId;
                    artifactVersion( groupId, artifactId, version, name, pomFilePath);
                }
            );

            // Add dependencies
            logger.info( "Accumulating dependencies" );
            visitPoms( repository, this::dependencies);

            txRoller.finish();
//            try ( Transaction tx = graphDatabaseService.beginTx() ) {
//                try (ResourceIterator<Node> whatNodes =
//                         graphDatabaseService.findNodes(MavenNodeLabel.Version)) {
//                    while (whatNodes.hasNext()) {
//                        Node node = whatNodes.next();
//                        System.out.println("groupId : " + node.getProperty(ATTR_ID_GROUP_ID));
//                    }
//                }
//            }
//
//            List<Node> whatNodes = new ArrayList<>();
//            graphDatabaseService.findNodes(MavenNodeLabel.groupId).forEachRemaining(whatNodes::add);
//            for (Node node: whatNodes) {
//                System.out.println("Group");
//                for (Map.Entry<String, Object> props
//                    : (node.getAllProperties() == null ? Collections.<String, Object>emptyMap() : node.getAllProperties()).entrySet()) {
//                    System.out.println("  " + props.getKey() + "; " + props.getValue());
//                }
//            }
        }
        catch (Exception e) {
            logger.error("while running: ", e);
        }
        finally
        {
            txRoller.finish();
            graphDatabaseService.shutdown();
        }

        if (!failedPoms.isEmpty()) {
            System.err.println("Failed POM files");
            for (String failedPom : failedPoms) {
                System.err.println(failedPom);
            }
        }
    }

    private void visitPoms( File repository, Visitor<Model> visitor) throws IOException, SAXException
    {
        if ( repository.isDirectory() )
        {
            File[] directories = repository.listFiles( new FileFilter()
            {
                public boolean accept( File pathname )
                {
                    return pathname.isDirectory();
                }
            } );

            // depth-first
            for ( File directory : directories ) {
                visitPoms( directory, visitor );
            }

            File[] poms = repository.listFiles( new FilenameFilter()
            {
                public boolean accept( File dir, String name )
                {
                    String[] components = dir.getPath().split("/");
                    return name.endsWith( components[components.length-1] + ".pom" );
                }
            } );

            for ( File pom : poms ) {
                visitPom( pom, visitor );
                txRoller.commit();
            }
        }
    }

    String pomFilePath;

    private void visitPom( File pomfile, Visitor<Model> visitor ) {
        try {
            Model model = modelResolver.resolve( pomfile );
            pomFilePath = pomfile.getAbsolutePath();
            visitor.accept( model );
        }
        catch ( Throwable e ) {
            LoggerFactory.getLogger( getClass() ).warn( "Could not handle: " + pomfile, e );
//            pomfile.delete();
            failedPoms.add( pomfile.getAbsolutePath() );
        }
    }


    Map<String, Node> versionNodesByName = new HashMap<>();
    private Node artifactVersion(String groupId, String artifactId, String version, String name, String pomFilePath) {
        logger.info("artifactVersion {} {} {}", groupId, artifactId, version);

        Node versionNode ;
        String artifactVersionName = makeTotalArtifactVersionId(groupId, artifactId, version);

        // we could look this up with our own map, too.
        try ( ResourceIterator<Node> versionNodes = graphDatabaseService.findNodes(
            MavenNodeLabel.Version, ATTR_ID_ARTIFACT_VERSION_NAME, artifactVersionName ) ) {
            if (versionNodes.hasNext()) {
                return versionNodes.next();
            }
        }
        // not already defined.
        versionNode = graphDatabaseService.createNode(MavenNodeLabel.Version);
        versionNode.setProperty( ATTR_ID_GROUP_ID, groupId );
        versionNode.setProperty( ATTR_ID_ARTIFACT_ID, artifactId );
        versionNode.setProperty( ATTR_ID_VERSION_ID, version );
        versionNode.setProperty( ATTR_ID_NAME, name );
        versionNode.setProperty( ATTR_ID_ARTIFACT_VERSION_NAME, artifactVersionName );
        versionNode.setProperty( ATTR_ID_POM_FILE_PATH, pomFilePath);
        Node tmp;
        if (null != (tmp = versionNodesByName.put(artifactVersionName, versionNode))) {
            logger.warn("for {}: nodes {} and {}", artifactVersionName, tmp.getId(), versionNode.getId());
        }
        Node groupIdNode;
        try ( ResourceIterator<Node> groupNodes = graphDatabaseService.findNodes(
            MavenNodeLabel.Group, ATTR_ID_GROUP_ID, groupId ) ) {
            if (groupNodes.hasNext()) {
                groupIdNode = groupNodes.next();
            }
            else {
                groupIdNode = graphDatabaseService.createNode(MavenNodeLabel.Group);
                groupIdNode.setProperty(ATTR_ID_GROUP_ID, groupId);
            }
        }

        Node artifactIdNode;
        try ( ResourceIterator<Node> artifactNodes = graphDatabaseService.findNodes(
            MavenNodeLabel.Artifact, ATTR_ID_ARTIFACT_ID, artifactId ) ) {
            if (artifactNodes.hasNext()) {
                artifactIdNode = artifactNodes.next();
            } else {
                artifactIdNode = graphDatabaseService.createNode(MavenNodeLabel.Artifact);
                artifactIdNode.setProperty(ATTR_ID_GROUP_ID, groupId);
                artifactIdNode.setProperty(ATTR_ID_ARTIFACT_ID, artifactId);
                artifactIdNode.setProperty(ATTR_ID_NAME, name );
                artifactIdNode.setProperty(ATTR_ID_POM_FILE_PATH, pomFilePath);
            }
        }

        if (artifactIdNode.getSingleRelationship( has_artifact, Direction.INCOMING ) == null) {
            groupIdNode.createRelationshipTo( artifactIdNode, has_artifact );
        }

        artifactIdNode.createRelationshipTo( versionNode, has_version );

        return versionNode;
    }

    private String makeTotalArtifactVersionId(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }

//    private void autoIndex( Index<Node> versions, Node node )
//    {
//        for ( String property : node.getPropertyKeys() )
//        {
//            versions.add(node, property, node.getProperty( property ));
//        }
//    }

    private String getVersion( Model model )
    {
        if (model.getVersion() == null)
            return model.getParent().getVersion();
        else
            return model.getVersion();
    }

    private String getGroupId( Model model )
    {
        if (model.getGroupId() == null)
            return model.getParent().getGroupId();
        else
            return model.getGroupId();
    }

    Map<String, Dependency> allDependencies = new HashMap<>();

    private void dependencies( final Model model )
    {
        visitVersion(
            getGroupId( model ),
            model.getArtifactId(),
            getVersion( model ),
            versionNode -> {
               // Found Artifact, now add dependencies
                for (Dependency dependency : model.getDependencies() ) {
                    // TODO handle different dependency nodes of test-jar, jar, war, etc. in scopes of test vs compile.
                    visitVersion(
                        dependency.getGroupId(),
                        dependency.getArtifactId(),
                        getVersion( dependency ),
                        dependencyVersionNode -> {
                            Iterable<Relationship> dependencies = versionNode.getRelationships(Direction.OUTGOING, has_dependency);
                            boolean isAlreadyPresent = false;
                            for (Relationship relationship : dependencies) {
                                if (relationship.getOtherNode(versionNode).getId() == dependencyVersionNode.getId()) {
                                    isAlreadyPresent = true;
                                    logger.warn(
                                        "dependency already present in {}: {} -> {}",
                                        pomFilePath,
                                        versionNode.getProperty(ATTR_ID_ARTIFACT_VERSION_NAME),
                                        dependencyVersionNode.getProperty(ATTR_ID_ARTIFACT_VERSION_NAME)
                                    );
                                    break;
                                }
                            }
                            if (isAlreadyPresent) {
                                //logger.warn("dependency already present!");
                                return;
                            }
                            Relationship dependencyRel = versionNode.createRelationshipTo(
                                    dependencyVersionNode, has_dependency );


                            logger.info("trace: {} -> {}", versionNode.getProperty(ATTR_ID_ARTIFACT_VERSION_NAME), dependencyVersionNode.getProperty(ATTR_ID_ARTIFACT_VERSION_NAME));

                            dependencyRel.setProperty( "scope", withDefault(dependency.getScope(), "compile" ));
                            dependencyRel.setProperty( "optional", withDefault(dependency.isOptional(), Boolean.FALSE ));
                        }
                    );
                }
            }
        );
        txRoller.commit();
    }

    private String getVersion( final Dependency dependency )
    {
        String version = dependency.getVersion();
        if (version.startsWith( "[" ))
        {
            version = version.substring( 1, version.indexOf( "," ) );
        }
        return version;
    }

    private boolean visitVersion( String groupId, String artifactId, String version, Visitor<Node> visitor )
    {
        String totalName = makeTotalArtifactVersionId(groupId, artifactId, version);
        try ( ResourceIterator<Node> versionNodes = graphDatabaseService.findNodes( MavenNodeLabel.Version, ATTR_ID_ARTIFACT_VERSION_NAME, totalName ) ) {
            while (versionNodes.hasNext()) {
                Node versionNode = versionNodes.next();
                if (versionNode.getProperty( ATTR_ID_ARTIFACT_ID ).equals( artifactId )
                    && versionNode.getProperty( ATTR_ID_GROUP_ID).equals( groupId )) {
                    visitor.accept( versionNode );
                    return true;
                }
            }

            logger.warn("faking relationship for {}", totalName);

            // Broken lookup - create fake node and mark as
            Node fakeNode = artifactVersion( groupId, artifactId, version, artifactId, pomFilePath );
            fakeNode.setProperty( "missing", true );
            visitor.accept( fakeNode );

            return false;
        }
    }

    private File pomFor(String groupId, String artifactId, String versionId)
    {
        File pom = repository;
        String[] groupIds = groupId.split( "\\." );
        for ( String id : groupIds )
        {
            pom = new File(pom, id);
        }

        pom = new File(pom, artifactId);

        pom = new File(pom, versionId );

        pom = new File(pom, artifactId+"-"+versionId+".pom");

        return pom;
    }

    interface Visitor<T>
    {
        void accept(T item);
    }

    public static <T> T withDefault(T value, T defaultValue)
    {
        return value == null ? defaultValue : value;
    }

}
