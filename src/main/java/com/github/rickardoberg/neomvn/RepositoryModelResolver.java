package com.github.rickardoberg.neomvn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.Parent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RepositoryModelResolver implements ModelResolver
{
    private final static Logger logger = LoggerFactory.getLogger(RepositoryModelResolver.class);
    private File repository;
    private String mavenRepository;

    private List<Repository> repositories = new ArrayList<Repository>(  );

    RepositoryModelResolver( File repository, String mavenRepository )
    {
        this.repository = repository;
        this.mavenRepository = mavenRepository;

        Repository mainRepo = new Repository();
        mainRepo.setUrl( mavenRepository );
        mainRepo.setId( "central" );
        repositories.add(mainRepo);
    }

    public ModelSource resolveModel( String groupId, String artifactId, String versionId ) throws UnresolvableModelException
    {
        File pom = getLocalFile( groupId, artifactId, versionId );

        if (!pom.exists())
        {
            // Download it
            try
            {
                download( pom );
            }
            catch ( IOException e )
            {
                throw new UnresolvableModelException( "Could not download POM", groupId, artifactId, versionId, e );
            }
        }

        return new FileModelSource( pom );
    }

    // Added to ensure that it builds with maven 3.3.9
    public ModelSource resolveModel( Parent parent ) throws UnresolvableModelException
    {
        File pom = getLocalFile( parent.getGroupId(), parent.getArtifactId(), parent.getVersion() );

        if (!pom.exists())
        {
            // Download it
            try
            {
                download( pom );
            }
            catch ( IOException e )
            {
                throw new UnresolvableModelException( "Could not download POM", parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), e );
            }
        }

        return new FileModelSource( pom );
    }
    
    public File getLocalFile( String groupId, String artifactId, String versionId )
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

    public void addRepository( Repository repository ) throws InvalidRepositoryException
    {
        for ( Repository existingRepository : repositories )
        {
            if (existingRepository.getId().equals( repository.getId() ))
                return;
        }
        
        repositories.add(repository);
    }

    // Added so that it will build correctly with maven 3.3.9
    public void addRepository( Repository repository, boolean replace ) throws InvalidRepositoryException
    {
        //
    }

    public ModelResolver newCopy()
    {
        return new RepositoryModelResolver( repository, mavenRepository );
    }

    public void download( File localRepoFile ) throws IOException
    {
        for ( Repository repository1 : repositories )
        {
            String repository1Url = repository1.getUrl();
            if (repository1Url.endsWith( "/" ))
                repository1Url = repository1Url.substring( 0, repository1Url.length()-1 );
            URL url = new URL( repository1Url + localRepoFile.getAbsolutePath().substring( repository.getAbsolutePath().length() ) );

            System.out.println("Downloading "+url);

            try
            {
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects( true );
                InputStream in = conn.getInputStream();
                localRepoFile.getParentFile().mkdirs();
                FileOutputStream out = new FileOutputStream( localRepoFile );
                byte[] buffer = new byte[4096];
                int len;
                while ((len = in.read( buffer )) != -1)
                {
                    out.write( buffer, 0, len );
                }
                in.close();
                out.close();
                return;
            }
            catch ( IOException e )
            {
                logger.error("failed to download " + url, e);
            }
        }

        throw new IOException( "Failed to download "+localRepoFile );
    }
}
