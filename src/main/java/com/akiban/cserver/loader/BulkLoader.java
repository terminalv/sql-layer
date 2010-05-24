package com.akiban.cserver.loader;

import com.akiban.ais.model.AkibaInformationSchema;
import com.akiban.ais.model.UserTable;
import com.akiban.cserver.store.Store;
import com.akiban.cserver.store.PersistitStore;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.sql.SQLException;
import java.util.*;

public class BulkLoader extends Thread
{
    // Thread interface

    @Override
    public void run()
    {
        try {
            DB db = new DB(dbHost, dbPort, dbUser, dbPassword);
            if (taskGeneratorActions == null) {
                taskGeneratorActions = new MySQLTaskGeneratorActions(ais);
            }
            IdentityHashMap<UserTable, TableTasks> tableTasksMap =
                new TaskGenerator(this, taskGeneratorActions).generateTasks();
            DataGrouper dataGrouper = new DataGrouper(db, artifactsSchema);
            if (resume) {
                dataGrouper.resume();
            } else {
                dataGrouper.run(tableTasksMap);
            }
            new PersistitLoader(persistitStore, db, ais).load(finalTasks(tableTasksMap));
            persistitStore.syncColumns(); /* temporary for now until VStore is more stabilized */
            if (cleanup) {
                dataGrouper.deleteWorkArea();
            }
            logger.info("Loading complete");
        } catch (Exception e) {
            logger.error("Bulk load terminated with exception", e);
            termination = e;
        }
    }

    // BulkLoader interface

    // For testing
    public BulkLoader(PersistitStore persistitStore,
                      AkibaInformationSchema ais,
                      List<String> groups,
                      String artifactsSchema,
                      Map<String, String> sourceSchemas,
                      String dbHost,
                      int dbPort,
                      String dbUser,
                      String dbPassword) throws ClassNotFoundException, SQLException
    {
        this.persistitStore = persistitStore;
        this.ais = ais;
        this.groups = groups;
        this.artifactsSchema = artifactsSchema;
        this.sourceSchemas = sourceSchemas;
        this.dbHost = dbHost;
        this.dbUser = dbUser;
        this.dbPort = dbPort;
        this.dbPassword = dbPassword;
    }

    public BulkLoader(Store store,
                      AkibaInformationSchema ais,
                      List<String> groups,
                      String artifactsSchema,
                      Map<String, String> sourceSchemas,
                      String dbHost,
                      int dbPort,
                      String dbUser,
                      String dbPassword,
                      boolean resume,
                      boolean cleanup) throws ClassNotFoundException, SQLException
    {
        this.persistitStore = (PersistitStore) store;
        this.ais = ais;
        this.groups = groups;
        this.artifactsSchema = artifactsSchema;
        this.sourceSchemas = sourceSchemas;
        this.dbHost = dbHost;
        this.dbUser = dbUser;
        this.dbPort = dbPort;
        this.dbPassword = dbPassword;
        this.resume = resume;
        this.cleanup = cleanup;
    }

    public Exception termination()
    {
        return termination;
    }

    // For use by this package

    String artifactsSchema()
    {
        return artifactsSchema;
    }

    String sourceSchema(String targetSchema)
    {
        String sourceSchema = sourceSchemas.get(targetSchema);
        if (sourceSchema == null) {
            sourceSchema = targetSchema;
        }
        return sourceSchema;
    }

    List<String> groups()
    {
        return groups;
    }

    AkibaInformationSchema ais()
    {
        return ais;
    }

    private static List<GenerateFinalTask> finalTasks(IdentityHashMap<UserTable, TableTasks> tableTasksMap)
    {
        List<GenerateFinalTask> finalTasks = new ArrayList<GenerateFinalTask>();
        for (TableTasks tableTasks : tableTasksMap.values()) {
            GenerateFinalTask finalTask = tableTasks.generateFinal();
            if (finalTask != null) {
                finalTasks.add(finalTask);
            }
        }
        return finalTasks;
    }

    private static final Log logger = LogFactory.getLog(BulkLoader.class.getName());

    private boolean resume = false;
    private boolean cleanup = true;
    private String dbHost;
    private int dbPort;
    private String dbUser;
    private String dbPassword;
    private String artifactsSchema;
    private List<String> groups;
    private Map<String, String> sourceSchemas;
    private PersistitStore persistitStore;
    private AkibaInformationSchema ais;
    private TaskGenerator.Actions taskGeneratorActions;
    private Exception termination = null;

    public static class RuntimeException extends java.lang.RuntimeException
    {
        RuntimeException(String message)
        {
            super(message);
        }

        RuntimeException(String message, Throwable th)
        {
            super(message, th);
        }
    }

    public static class InternalError extends java.lang.Error
    {
        InternalError(String message)
        {
            super(message);
        }
    }
}
