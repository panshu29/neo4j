/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.systemgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.neo4j.cypher.internal.security.SecureHasher;
import org.neo4j.cypher.internal.security.SystemGraphCredential;
import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.dbms.database.SystemGraphInitializer;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.security.AuthProviderFailedException;
import org.neo4j.kernel.impl.security.Credential;
import org.neo4j.kernel.impl.security.User;
import org.neo4j.logging.Log;
import org.neo4j.server.security.auth.ListSnapshot;
import org.neo4j.server.security.auth.UserRepository;
import org.neo4j.string.UTF8;

import static org.neo4j.configuration.GraphDatabaseSettings.SYSTEM_DATABASE_NAME;
import static org.neo4j.kernel.api.security.AuthManager.INITIAL_PASSWORD;
import static org.neo4j.kernel.api.security.AuthManager.INITIAL_USER_NAME;
import static org.neo4j.kernel.database.DatabaseIdRepository.SYSTEM_DATABASE_ID;

public class UserSecurityGraphInitializer implements SecurityGraphInitializer
{
    protected Label USER_LABEL = Label.label( "User" );
    protected List<Node> userNodes = new ArrayList<>();
    protected List<String> usernames = new ArrayList<>();

    protected final DatabaseManager<?> databaseManager;
    protected GraphDatabaseService systemDb;
    protected final SystemGraphInitializer systemGraphInitializer;
    protected Log log;

    private final Supplier<UserRepository> migrationUserRepositorySupplier;
    private final Supplier<UserRepository> initialUserRepositorySupplier;
    private final SecureHasher secureHasher;

    public UserSecurityGraphInitializer( DatabaseManager<?> databaseManager, SystemGraphInitializer systemGraphInitializer, Log log,
            Supplier<UserRepository> migrationUserRepositorySupplier, Supplier<UserRepository> initialUserRepositorySupplier, SecureHasher secureHasher )
    {
        this.databaseManager = databaseManager;
        this.systemGraphInitializer = systemGraphInitializer;
        this.log = log;
        this.migrationUserRepositorySupplier = migrationUserRepositorySupplier;
        this.initialUserRepositorySupplier = initialUserRepositorySupplier;
        this.secureHasher = secureHasher;
    }

    public void initializeSecurityGraph() throws Exception
    {
        initializeSecurityGraph( getSystemDb() );
    }

    @Override
    public void initializeSecurityGraph( GraphDatabaseService database ) throws Exception
    {
        systemGraphInitializer.initializeSystemGraph( database );
        systemDb = database;
        doInitializeSecurityGraph();
    }

    private void doInitializeSecurityGraph() throws Exception
    {
        // Must be done outside main transaction since it changes the schema
        setupConstraints();

        try ( Transaction tx = systemDb.beginTx() )
        {
            userNodes = findInitialNodes( tx, USER_LABEL );
            userNodes.stream().filter( node -> node.hasProperty( "name" ) && node.getProperty( "name" ).getClass().equals( String.class ) )
                    .forEach( node -> usernames.add( (String) node.getProperty( "name" ) ) );

            boolean initialUsersExist = !userNodes.isEmpty();

            // If the security graph had not been initialized (typically the first time you start neo4j),
            // try to migrate users from the auth file.
            if ( !initialUsersExist )
            {
                initialUsersExist = migrateFromAuthFile( tx );
            }

            // If no users were migrated, create the default user with the default password
            if ( !initialUsersExist )
            {
                addDefaultUser( tx );
            }

            // If applicable, give the default user the password set by set-initial-password command
            setInitialPassword( );

            tx.commit();
        }
    }

    private void setupConstraints()
    {
        // Ensure that multiple users cannot have the same name and are indexed
        try ( Transaction tx = systemDb.beginTx() )
        {
            try
            {
                tx.schema().constraintFor( USER_LABEL ).assertPropertyIsUnique( "name" ).create();
            }
            catch ( ConstraintViolationException e )
            {
                // Makes the creation of constraints for security idempotent
                if ( !e.getMessage().startsWith( "An equivalent constraint already exists" ) )
                {
                    throw e;
                }
            }
            tx.commit();
        }
    }

    protected ArrayList<Node> findInitialNodes( Transaction tx, Label label )
    {
        ArrayList<Node> nodeList = new ArrayList<>();
        final ResourceIterator<Node> nodes = tx.findNodes( label );
        nodes.forEachRemaining( nodeList::add );
        nodes.close();

        return nodeList;
    }

    private boolean migrateFromAuthFile( Transaction tx ) throws Exception
    {
        UserRepository userRepository = startUserRepository( migrationUserRepositorySupplier );
        boolean migratedUsers = doImportUsers( tx, userRepository );
        stopUserRepository( userRepository );
        return migratedUsers;
    }

    protected boolean doImportUsers( Transaction tx, UserRepository userRepository ) throws Exception
    {
        ListSnapshot<User> users = userRepository.getPersistedSnapshot();

        if ( !users.values().isEmpty() )
        {
            for ( User user : users.values() )
            {
                addUser( tx, user.name(), user.credentials(), user.passwordChangeRequired(), user.hasFlag( BasicSystemGraphRealm.IS_SUSPENDED ) );
            }

            // Log what happened to the security log
            String userString = users.values().size() == 1 ? "user" : "users";
            log.info( "Completed import of %s %s into system graph.", Integer.toString( users.values().size() ), userString );
            return true;
        }
        return false;
    }

    /* Adds initial neo4j user */
    protected void addDefaultUser( Transaction tx )
    {
        SystemGraphCredential initialCredential = SystemGraphCredential.createCredentialForPassword( UTF8.encode( INITIAL_PASSWORD ), secureHasher );
        addUser( tx, INITIAL_USER_NAME, initialCredential, true, false );
    }

    protected UserRepository startUserRepository( Supplier<UserRepository> supplier ) throws Exception
    {
        UserRepository userRepository = supplier.get();
        userRepository.init();
        userRepository.start();
        return userRepository;
    }

    protected void stopUserRepository( UserRepository userRepository ) throws Exception
    {
        userRepository.stop();
        userRepository.shutdown();
    }

    protected void setInitialPassword( ) throws Exception
    {
        // The set-initial-password command should only take effect if the only existing user is the default user with the default password.
        // The reason for this user to already exist in the system database can e.g. be if some setup script have led to the dbms
        // being started before first login.
        if ( userNodes.size() == 1 )
        {
            Node defaultUser = userNodes.get( 0 );

            if ( defaultUser.getProperty( "name" ).equals( INITIAL_USER_NAME ) )
            {
                Credential credentials = SystemGraphCredential.deserialize( (String) defaultUser.getProperty( "credentials" ), secureHasher );
                if ( credentials.matchesPassword( UTF8.encode( INITIAL_PASSWORD ) ) )
                {
                    Credential credential = getInitialPassword();
                    if ( credential != null )
                    {
                        defaultUser.setProperty( "credentials", credential.serialize() );
                        defaultUser.setProperty( "passwordChangeRequired", false );
                    }
                }
            }
        }
    }

    private Credential getInitialPassword() throws Exception
    {
        Credential credential = null;
        if ( initialUserRepositorySupplier != null )
        {
            UserRepository initialUserRepository = startUserRepository( initialUserRepositorySupplier );
            if ( initialUserRepository.numberOfUsers() > 0 )
            {
                // In alignment with InternalFlatFileRealm we only allow the INITIAL_USER_NAME here for now
                // (This is what we get from the `set-initial-password` command)
                User initialUser = initialUserRepository.getUserByName( INITIAL_USER_NAME );
                if ( initialUser != null )
                {
                    credential = initialUser.credentials();
                }
            }
            stopUserRepository( initialUserRepository );
        }
        return credential;
    }

    private void addUser( Transaction tx, String username, Credential credentials, boolean passwordChangeRequired, boolean suspended )
    {
        // NOTE: If username already exists we will violate a constraint
        Node node = tx.createNode( USER_LABEL );
        node.setProperty( "name", username );
        node.setProperty( "credentials", credentials.serialize() );
        node.setProperty( "passwordChangeRequired", passwordChangeRequired );
        node.setProperty( "suspended", suspended );
        userNodes.add( node );
        usernames.add( username );
    }

    protected GraphDatabaseService getSystemDb()
    {
        return databaseManager.getDatabaseContext( SYSTEM_DATABASE_ID ).orElseThrow(
                () -> new AuthProviderFailedException( "No database called `" + SYSTEM_DATABASE_NAME + "` was found." ) ).databaseFacade();
    }
}
