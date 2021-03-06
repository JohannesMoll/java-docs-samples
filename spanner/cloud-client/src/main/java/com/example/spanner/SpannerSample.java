/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.spanner;

import static com.google.cloud.spanner.TransactionRunner.TransactionCallable;

import com.google.cloud.spanner.Database;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Operation;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.Value;
import com.google.spanner.admin.database.v1.CreateDatabaseMetadata;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Example code for using the Cloud Spanner API. This example demonstrates all the common
 * operations that can be done on Cloud Spanner. These are: <p>
 * <ul>
 * <li> Creating a Cloud Spanner database.
 * <li> Writing, reading and executing SQL queries.
 * <li> Writing data using a read-write transaction.
 * <li> Using an index to read and execute SQL queries over data.
 * <li> Using commit timestamp for tracking when a record was last updated.
 * </ul>
 */
public class SpannerSample {

  /**
   * Class to contain singer sample data.
   */
  static class Singer {

    final long singerId;
    final String firstName;
    final String lastName;

    Singer(long singerId, String firstName, String lastName) {
      this.singerId = singerId;
      this.firstName = firstName;
      this.lastName = lastName;
    }
  }

  /**
   * Class to contain album sample data.
   */
  static class Album {

    final long singerId;
    final long albumId;
    final String albumTitle;

    Album(long singerId, long albumId, String albumTitle) {
      this.singerId = singerId;
      this.albumId = albumId;
      this.albumTitle = albumTitle;
    }
  }

  /**
  * Class to contain performance sample data.
  */
  static class Performance {

    final long singerId;
    final long venueId;
    final String eventDate;
    final long revenue;

    Performance(long singerId, long venueId, String eventDate, long revenue) {
      this.singerId = singerId;
      this.venueId = venueId;
      this.eventDate = eventDate;
      this.revenue = revenue;
    }
  }

  // [START spanner_insert_data]
  static final List<Singer> SINGERS =
      Arrays.asList(
          new Singer(1, "Marc", "Richards"),
          new Singer(2, "Catalina", "Smith"),
          new Singer(3, "Alice", "Trentor"),
          new Singer(4, "Lea", "Martin"),
          new Singer(5, "David", "Lomond"));

  static final List<Album> ALBUMS =
      Arrays.asList(
          new Album(1, 1, "Total Junk"),
          new Album(1, 2, "Go, Go, Go"),
          new Album(2, 1, "Green"),
          new Album(2, 2, "Forever Hold Your Peace"),
          new Album(2, 3, "Terrified"));
  // [END spanner_insert_data]

  // [START spanner_insert_data_with_timestamp_column]
  static final List<Performance> PERFORMANCES =
      Arrays.asList(
          new Performance(1, 4, "2017-10-05", 11000),
          new Performance(1, 19, "2017-11-02", 15000),
          new Performance(2, 42, "2017-12-23", 7000));
  // [END spanner_insert_data_with_timestamp_column]

  // [START spanner_create_database]
  static void createDatabase(DatabaseAdminClient dbAdminClient, DatabaseId id) {
    Operation<Database, CreateDatabaseMetadata> op = dbAdminClient
        .createDatabase(
            id.getInstanceId().getInstance(),
            id.getDatabase(),
            Arrays.asList(
                "CREATE TABLE Singers (\n"
                    + "  SingerId   INT64 NOT NULL,\n"
                    + "  FirstName  STRING(1024),\n"
                    + "  LastName   STRING(1024),\n"
                    + "  SingerInfo BYTES(MAX)\n"
                    + ") PRIMARY KEY (SingerId)",
                "CREATE TABLE Albums (\n"
                    + "  SingerId     INT64 NOT NULL,\n"
                    + "  AlbumId      INT64 NOT NULL,\n"
                    + "  AlbumTitle   STRING(MAX)\n"
                    + ") PRIMARY KEY (SingerId, AlbumId),\n"
                    + "  INTERLEAVE IN PARENT Singers ON DELETE CASCADE"));
    Database db = op.waitFor().getResult();
    System.out.println("Created database [" + db.getId() + "]");
  }
  // [END spanner_create_database]

  // [START spanner_create_table_with_timestamp_column]
  static void createTableWithTimestamp(DatabaseAdminClient dbAdminClient, DatabaseId id) {
    Operation<Void, UpdateDatabaseDdlMetadata> op = dbAdminClient
        .updateDatabaseDdl(
            id.getInstanceId().getInstance(),
            id.getDatabase(),
            Arrays.asList(
                "CREATE TABLE Performances (\n"
                    + "  SingerId     INT64 NOT NULL,\n"
                    + "  VenueId      INT64 NOT NULL,\n"
                    + "  EventDate    Date,\n"
                    + "  Revenue      INT64, \n"
                    + "  LastUpdateTime TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true)\n"
                    + ") PRIMARY KEY (SingerId, VenueId, EventDate),\n"
                    + "  INTERLEAVE IN PARENT Singers ON DELETE CASCADE"), null);
    op.waitFor().getResult();
    System.out.println("Created Performances table in database: [" + id + "]");
  }
  // [END spanner_create_table_with_timestamp_column]

  // [START spanner_insert_data_with_timestamp_column]
  static void writeExampleDataWithTimestamp(DatabaseClient dbClient) {
    List<Mutation> mutations = new ArrayList<>();
    for (Performance performance : PERFORMANCES) {
      mutations.add(
          Mutation.newInsertBuilder("Performances")
              .set("SingerId")
              .to(performance.singerId)
              .set("VenueId")
              .to(performance.venueId)
              .set("EventDate")
              .to(performance.eventDate)
              .set("Revenue")
              .to(performance.revenue)
              .set("LastUpdateTime")
              .to(Value.COMMIT_TIMESTAMP)
              .build());
    }
    dbClient.write(mutations);
  }
  // [END spanner_insert_data_with_timestamp_column]

  // [START spanner_insert_data]
  static void writeExampleData(DatabaseClient dbClient) {
    List<Mutation> mutations = new ArrayList<>();
    for (Singer singer : SINGERS) {
      mutations.add(
          Mutation.newInsertBuilder("Singers")
              .set("SingerId")
              .to(singer.singerId)
              .set("FirstName")
              .to(singer.firstName)
              .set("LastName")
              .to(singer.lastName)
              .build());
    }
    for (Album album : ALBUMS) {
      mutations.add(
          Mutation.newInsertBuilder("Albums")
              .set("SingerId")
              .to(album.singerId)
              .set("AlbumId")
              .to(album.albumId)
              .set("AlbumTitle")
              .to(album.albumTitle)
              .build());
    }
    dbClient.write(mutations);
  }
  // [END spanner_insert_data]

  // [START spanner_query_data]
  static void query(DatabaseClient dbClient) {
    // singleUse() can be used to execute a single read or query against Cloud Spanner.
    ResultSet resultSet =
        dbClient
            .singleUse()
            .executeQuery(Statement.of("SELECT SingerId, AlbumId, AlbumTitle FROM Albums"));
    while (resultSet.next()) {
      System.out.printf(
          "%d %d %s\n", resultSet.getLong(0), resultSet.getLong(1), resultSet.getString(2));
    }
  }
  // [END spanner_query_data]

  // [START spanner_read_data]
  static void read(DatabaseClient dbClient) {
    ResultSet resultSet =
        dbClient
            .singleUse()
            .read("Albums",
                // KeySet.all() can be used to read all rows in a table. KeySet exposes other
                // methods to read only a subset of the table.
                KeySet.all(),
                Arrays.asList("SingerId", "AlbumId", "AlbumTitle"));
    while (resultSet.next()) {
      System.out.printf(
          "%d %d %s\n", resultSet.getLong(0), resultSet.getLong(1), resultSet.getString(2));
    }
  }
  // [END spanner_read_data]

  // [START spanner_add_column]
  static void addMarketingBudget(DatabaseAdminClient adminClient, DatabaseId dbId) {
    adminClient.updateDatabaseDdl(dbId.getInstanceId().getInstance(),
        dbId.getDatabase(),
        Arrays.asList("ALTER TABLE Albums ADD COLUMN MarketingBudget INT64"),
        null).waitFor();
    System.out.println("Added MarketingBudget column");
  }
  // [END spanner_add_column]

  // Before executing this method, a new column MarketingBudget has to be added to the Albums
  // table by applying the DDL statement "ALTER TABLE Albums ADD COLUMN MarketingBudget INT64".
  // [START spanner_update_data]
  static void update(DatabaseClient dbClient) {
    // Mutation can be used to update/insert/delete a single row in a table. Here we use
    // newUpdateBuilder to create update mutations.
    List<Mutation> mutations =
        Arrays.asList(
            Mutation.newUpdateBuilder("Albums")
                .set("SingerId")
                .to(1)
                .set("AlbumId")
                .to(1)
                .set("MarketingBudget")
                .to(100000)
                .build(),
            Mutation.newUpdateBuilder("Albums")
                .set("SingerId")
                .to(2)
                .set("AlbumId")
                .to(2)
                .set("MarketingBudget")
                .to(500000)
                .build());
    // This writes all the mutations to Cloud Spanner atomically.
    dbClient.write(mutations);
  }
  // [END spanner_update_data]

  // [START spanner_read_write_transaction]
  static void writeWithTransaction(DatabaseClient dbClient) {
    dbClient
        .readWriteTransaction()
        .run(
            new TransactionCallable<Void>() {
              @Override
              public Void run(TransactionContext transaction) throws Exception {
                // Transfer marketing budget from one album to another. We do it in a transaction to
                // ensure that the transfer is atomic.
                Struct row =
                    transaction.readRow("Albums", Key.of(2, 2), Arrays.asList("MarketingBudget"));
                long album2Budget = row.getLong(0);
                // Transaction will only be committed if this condition still holds at the time of
                // commit. Otherwise it will be aborted and the callable will be rerun by the
                // client library.
                if (album2Budget >= 300000) {
                  long album1Budget =
                      transaction
                          .readRow("Albums", Key.of(1, 1), Arrays.asList("MarketingBudget"))
                          .getLong(0);
                  long transfer = 200000;
                  album1Budget += transfer;
                  album2Budget -= transfer;
                  transaction.buffer(
                      Mutation.newUpdateBuilder("Albums")
                          .set("SingerId")
                          .to(1)
                          .set("AlbumId")
                          .to(1)
                          .set("MarketingBudget")
                          .to(album1Budget)
                          .build());
                  transaction.buffer(
                      Mutation.newUpdateBuilder("Albums")
                          .set("SingerId")
                          .to(2)
                          .set("AlbumId")
                          .to(2)
                          .set("MarketingBudget")
                          .to(album2Budget)
                          .build());
                }
                return null;
              }
            });
  }
  // [END spanner_read_write_transaction]

  // [START spanner_query_data_with_new_column]
  static void queryMarketingBudget(DatabaseClient dbClient) {
    // Rows without an explicit value for MarketingBudget will have a MarketingBudget equal to
    // null.
    ResultSet resultSet =
        dbClient
            .singleUse()
            .executeQuery(Statement.of("SELECT SingerId, AlbumId, MarketingBudget FROM Albums"));
    while (resultSet.next()) {
      System.out.printf(
          "%d %d %s\n",
          resultSet.getLong("SingerId"),
          resultSet.getLong("AlbumId"),
          // We check that the value is non null. ResultSet getters can only be used to retrieve
          // non null values.
          resultSet.isNull("MarketingBudget") ? "NULL" : resultSet.getLong("MarketingBudget"));
    }
  }
  // [END spanner_query_data_with_new_column]

  // [START spanner_create_index]
  static void addIndex(DatabaseAdminClient adminClient, DatabaseId dbId) {
    adminClient.updateDatabaseDdl(dbId.getInstanceId().getInstance(),
        dbId.getDatabase(),
        Arrays.asList("CREATE INDEX AlbumsByAlbumTitle ON Albums(AlbumTitle)"),
        null).waitFor();
    System.out.println("Added AlbumsByAlbumTitle index");
  }
  // [END spanner_create_index]

  // Before running this example, add the index AlbumsByAlbumTitle by applying the DDL statement
  // "CREATE INDEX AlbumsByAlbumTitle ON Albums(AlbumTitle)".
  // [START spanner_query_data_with_index]
  static void queryUsingIndex(DatabaseClient dbClient) {
    Statement statement = Statement
        // We use FORCE_INDEX hint to specify which index to use. For more details see
        // https://cloud.google.com/spanner/docs/query-syntax#from-clause
        .newBuilder("SELECT AlbumId, AlbumTitle, MarketingBudget\n"
            + "FROM Albums@{FORCE_INDEX=AlbumsByAlbumTitle}\n"
            + "WHERE AlbumTitle >= @StartTitle AND AlbumTitle < @EndTitle")
        // We use @BoundParameters to help speed up frequently executed queries.
        //  For more details see https://cloud.google.com/spanner/docs/sql-best-practices
        .bind("StartTitle").to("Aardvark")
        .bind("EndTitle").to("Goo")
        .build();

    ResultSet resultSet = dbClient.singleUse().executeQuery(statement);
    while (resultSet.next()) {
      System.out.printf(
          "%d %s %s\n",
          resultSet.getLong("AlbumId"),
          resultSet.getString("AlbumTitle"),
          resultSet.isNull("MarketingBudget") ? "NULL" : resultSet.getLong("MarketingBudget"));
    }
  }
  // [END spanner_query_data_with_index]

  // [START spanner_read_data_with_index]
  static void readUsingIndex(DatabaseClient dbClient) {
    ResultSet resultSet =
        dbClient
            .singleUse()
            .readUsingIndex(
                "Albums",
                "AlbumsByAlbumTitle",
                KeySet.all(),
                Arrays.asList("AlbumId", "AlbumTitle"));
    while (resultSet.next()) {
      System.out.printf("%d %s\n", resultSet.getLong(0), resultSet.getString(1));
    }
  }
  // [END spanner_read_data_with_index]

  // [START spanner_create_storing_index]
  static void addStoringIndex(DatabaseAdminClient adminClient, DatabaseId dbId) {
    adminClient.updateDatabaseDdl(dbId.getInstanceId().getInstance(),
        dbId.getDatabase(),
        Arrays.asList(
            "CREATE INDEX AlbumsByAlbumTitle2 ON Albums(AlbumTitle) STORING (MarketingBudget)"),
        null).waitFor();
    System.out.println("Added AlbumsByAlbumTitle2 index");
  }
  // [END spanner_create_storing_index]

  // Before running this example, create a storing index AlbumsByAlbumTitle2 by applying the DDL
  // statement "CREATE INDEX AlbumsByAlbumTitle2 ON Albums(AlbumTitle) STORING (MarketingBudget)".
  // [START spanner_read_data_with_storing_index]
  static void readStoringIndex(DatabaseClient dbClient) {
    // We can read MarketingBudget also from the index since it stores a copy of MarketingBudget.
    ResultSet resultSet =
        dbClient
            .singleUse()
            .readUsingIndex(
                "Albums",
                "AlbumsByAlbumTitle2",
                KeySet.all(),
                Arrays.asList("AlbumId", "AlbumTitle", "MarketingBudget"));
    while (resultSet.next()) {
      System.out.printf(
          "%d %s %s\n",
          resultSet.getLong(0),
          resultSet.getString(1),
          resultSet.isNull("MarketingBudget") ? "NULL" : resultSet.getLong("MarketingBudget"));
    }
  }
  // [END spanner_read_data_with_storing_index]

  // [START spanner_read_only_transaction]
  static void readOnlyTransaction(DatabaseClient dbClient) {
    // ReadOnlyTransaction must be closed by calling close() on it to release resources held by it.
    // We use a try-with-resource block to automatically do so.
    try (ReadOnlyTransaction transaction = dbClient.readOnlyTransaction()) {
      ResultSet queryResultSet =
          transaction.executeQuery(
              Statement.of("SELECT SingerId, AlbumId, AlbumTitle FROM Albums"));
      while (queryResultSet.next()) {
        System.out.printf(
            "%d %d %s\n",
            queryResultSet.getLong(0), queryResultSet.getLong(1), queryResultSet.getString(2));
      }
      ResultSet readResultSet =
          transaction.read(
              "Albums", KeySet.all(), Arrays.asList("SingerId", "AlbumId", "AlbumTitle"));
      while (readResultSet.next()) {
        System.out.printf(
            "%d %d %s\n",
            readResultSet.getLong(0), readResultSet.getLong(1), readResultSet.getString(2));
      }
    }
  }
  // [END spanner_read_only_transaction]

  // [START spanner_read_stale_data]
  static void readStaleData(DatabaseClient dbClient) {
    ResultSet resultSet =
        dbClient
            .singleUse(TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS))
            .read("Albums",
                KeySet.all(),
                Arrays.asList("SingerId", "AlbumId", "MarketingBudget"));
    while (resultSet.next()) {
      System.out.printf(
          "%d %d %s\n", resultSet.getLong(0), resultSet.getLong(1),
          resultSet.isNull(2) ? "NULL" : resultSet.getLong("MarketingBudget"));
    }
  }
  // [END spanner_read_stale_data]

  // [START spanner_add_timestamp_column]
  static void addCommitTimestamp(DatabaseAdminClient adminClient, DatabaseId dbId) {
    adminClient.updateDatabaseDdl(dbId.getInstanceId().getInstance(),
        dbId.getDatabase(),
        Arrays.asList(
            "ALTER TABLE Albums ADD COLUMN LastUpdateTime TIMESTAMP "
            + "OPTIONS (allow_commit_timestamp=true)"),
        null).waitFor();
    System.out.println("Added LastUpdateTime as a commit timestamp column in Albums table.");
  }
  // [END spanner_add_timestamp_column]

  // Before executing this method, a new column MarketingBudget has to be added to the Albums
  // table by applying the DDL statement "ALTER TABLE Albums ADD COLUMN MarketingBudget INT64".
  // In addition this update expects the LastUpdateTime column added by applying the DDL statement
  // "ALTER TABLE Albums ADD COLUMN LastUpdateTime TIMESTAMP OPTIONS (allow_commit_timestamp=true)"
  // [START spanner_update_data_with_timestamp_column]
  static void updateWithTimestamp(DatabaseClient dbClient) {
    // Mutation can be used to update/insert/delete a single row in a table. Here we use
    // newUpdateBuilder to create update mutations.
    List<Mutation> mutations =
        Arrays.asList(
            Mutation.newUpdateBuilder("Albums")
                .set("SingerId")
                .to(1)
                .set("AlbumId")
                .to(1)
                .set("MarketingBudget")
                .to(1000000)
                .set("LastUpdateTime")
                .to(Value.COMMIT_TIMESTAMP)
                .build(),
            Mutation.newUpdateBuilder("Albums")
                .set("SingerId")
                .to(2)
                .set("AlbumId")
                .to(2)
                .set("MarketingBudget")
                .to(750000)
                .set("LastUpdateTime")
                .to(Value.COMMIT_TIMESTAMP)
                .build());
    // This writes all the mutations to Cloud Spanner atomically.
    dbClient.write(mutations);
  }
  // [END spanner_update_data_with_timestamp_column]

  // [START spanner_query_data_with_timestamp_column]
  static void queryMarketingBudgetWithTimestamp(DatabaseClient dbClient) {
    // Rows without an explicit value for MarketingBudget will have a MarketingBudget equal to
    // null.
    ResultSet resultSet =
        dbClient
            .singleUse()
            .executeQuery(Statement.of(
                "SELECT SingerId, AlbumId, MarketingBudget, LastUpdateTime FROM Albums"
                + " ORDER BY LastUpdateTime DESC"));
    while (resultSet.next()) {
      System.out.printf(
          "%d %d %s %s\n",
          resultSet.getLong("SingerId"),
          resultSet.getLong("AlbumId"),
          // We check that the value is non null. ResultSet getters can only be used to retrieve
          // non null values.
          resultSet.isNull("MarketingBudget") ? "NULL" : resultSet.getLong("MarketingBudget"),
          resultSet.isNull("LastUpdateTime") ? "NULL" : resultSet.getTimestamp("LastUpdateTime"));
    }
  }
  // [END spanner_query_data_with_timestamp_column]

  static void queryPerformancesTable(DatabaseClient dbClient) {
    // Rows without an explicit value for Revenue will have a Revenue equal to
    // null.
    ResultSet resultSet =
        dbClient
            .singleUse()
            .executeQuery(Statement.of(
                "SELECT SingerId, VenueId, EventDate, Revenue, LastUpdateTime FROM Performances"
                + " ORDER BY LastUpdateTime DESC"));
    while (resultSet.next()) {
      System.out.printf(
          "%d %d %s %s %s\n",
          resultSet.getLong("SingerId"),
          resultSet.getLong("VenueId"),
          resultSet.getDate("EventDate"),
          // We check that the value is non null. ResultSet getters can only be used to retrieve
          // non null values.
          resultSet.isNull("Revenue") ? "NULL" : resultSet.getLong("Revenue"),
          resultSet.getTimestamp("LastUpdateTime"));
    }
  }

  static void run(DatabaseClient dbClient, DatabaseAdminClient dbAdminClient, String command,
      DatabaseId database) {
    switch (command) {
      case "createdatabase":
        createDatabase(dbAdminClient, database);
        break;
      case "write":
        writeExampleData(dbClient);
        break;
      case "query":
        query(dbClient);
        break;
      case "read":
        read(dbClient);
        break;
      case "addmarketingbudget":
        addMarketingBudget(dbAdminClient, database);
        break;
      case "update":
        update(dbClient);
        break;
      case "writetransaction":
        writeWithTransaction(dbClient);
        break;
      case "querymarketingbudget":
        queryMarketingBudget(dbClient);
        break;
      case "addindex":
        addIndex(dbAdminClient, database);
        break;
      case "readindex":
        readUsingIndex(dbClient);
        break;
      case "queryindex":
        queryUsingIndex(dbClient);
        break;
      case "addstoringindex":
        addStoringIndex(dbAdminClient, database);
        break;
      case "readstoringindex":
        readStoringIndex(dbClient);
        break;
      case "readonlytransaction":
        readOnlyTransaction(dbClient);
        break;
      case "readstaledata":
        readStaleData(dbClient);
        break;
      case "addcommittimestamp":
        addCommitTimestamp(dbAdminClient, database);
        break;
      case "updatewithtimestamp":
        updateWithTimestamp(dbClient);
        break;
      case "querywithtimestamp":
        queryMarketingBudgetWithTimestamp(dbClient);
        break;
      case "createtablewithtimestamp":
        createTableWithTimestamp(dbAdminClient, database);
        break;
      case "writewithtimestamp":
        writeExampleDataWithTimestamp(dbClient);
        break;
      case "queryperformancestable":
        queryPerformancesTable(dbClient);
        break;
      default:
        printUsageAndExit();
    }
  }

  static void printUsageAndExit() {
    System.err.println("Usage:");
    System.err.println("    SpannerExample <command> <instance_id> <database_id>");
    System.err.println("");
    System.err.println("Examples:");
    System.err.println(
        "    SpannerExample createdatabase my-instance example-db");
    System.err.println(
        "    SpannerExample write my-instance example-db");
    System.err.println(
        "    SpannerExample query my-instance example-db");
    System.err.println(
        "    SpannerExample read my-instance example-db");
    System.err.println(
        "    SpannerExample addmarketingbudget my-instance example-db");
    System.err.println(
        "    SpannerExample update my-instance example-db");
    System.err.println(
        "    SpannerExample writetransaction my-instance example-db");
    System.err.println(
        "    SpannerExample querymarketingbudget my-instance example-db");
    System.err.println(
        "    SpannerExample addindex my-instance example-db");
    System.err.println(
        "    SpannerExample readindex my-instance example-db");
    System.err.println(
        "    SpannerExample queryindex my-instance example-db");
    System.err.println(
        "    SpannerExample addstoringindex my-instance example-db");
    System.err.println(
        "    SpannerExample readstoringindex my-instance example-db");
    System.err.println(
        "    SpannerExample readonlytransaction my-instance example-db");
    System.err.println(
        "    SpannerExample readstaledata my-instance example-db");
    System.err.println(
        "    SpannerExample addcommittimestamp my-instance example-db");
    System.err.println(
        "    SpannerExample updatewithtimestamp my-instance example-db");
    System.err.println(
        "    SpannerExample querywithtimestamp my-instance example-db");
    System.err.println(
        "    SpannerExample createtablewithtimestamp my-instance example-db");
    System.err.println(
        "    SpannerExample writewithtimestamp my-instance example-db");
    System.err.println(
        "    SpannerExample queryperformancestable my-instance example-db");
    System.exit(1);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      printUsageAndExit();
    }
    // [START init_client]
    SpannerOptions options = SpannerOptions.newBuilder().build();
    Spanner spanner = options.getService();
    try {
      String command = args[0];
      DatabaseId db = DatabaseId.of(options.getProjectId(), args[1], args[2]);
      // [END init_client]
      // This will return the default project id based on the environment.
      String clientProject = spanner.getOptions().getProjectId();
      if (!db.getInstanceId().getProject().equals(clientProject)) {
        System.err.println("Invalid project specified. Project in the database id should match"
            + "the project name set in the environment variable GCLOUD_PROJECT. Expected: "
            + clientProject);
        printUsageAndExit();
      }
      // [START init_client]
      DatabaseClient dbClient = spanner.getDatabaseClient(db);
      DatabaseAdminClient dbAdminClient = spanner.getDatabaseAdminClient();
      // [END init_client]
      run(dbClient, dbAdminClient, command, db);
    } finally {
      spanner.close();
    }
    System.out.println("Closed client");
  }
}
