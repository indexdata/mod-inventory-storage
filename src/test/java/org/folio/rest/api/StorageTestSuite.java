package org.folio.rest.api;

import static org.folio.utility.KafkaUtility.startKafka;
import static org.folio.utility.KafkaUtility.stopKafka;
import static org.folio.utility.RestUtility.TENANT_ID;
import static org.folio.utility.VertxUtility.getVertx;
import static org.folio.utility.VertxUtility.removeTenant;
import static org.folio.utility.VertxUtility.startVertx;
import static org.folio.utility.VertxUtility.stopVertx;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import io.vertx.core.Promise;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.SneakyThrows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.impl.StorageHelperTest;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.support.HttpClient;
import org.folio.rest.support.Response;
import org.folio.rest.support.ResponseHandler;
import org.folio.rest.unit.ItemDamagedStatusAPIUnitTest;
import org.folio.services.CallNumberUtilsTest;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
  AsyncMigrationTest.class,
  PublicationPeriodMigrationTest.class,
  CallNumberUtilsTest.class,
  InstanceStorageTest.class,
  RecordBulkTest.class,
  HoldingsStorageTest.class,
  ItemStorageTest.class,
  DereferencedItemStorageTest.class,
  HoldingsTypeTest.class,
  LoanTypeTest.class,
  MaterialTypeTest.class,
  ContributorTypesTest.class,
  ShelfLocationsTest.class,
  LocationUnitTest.class,
  LocationsTest.class,
  ServicePointTest.class,
  ServicePointsUserTest.class,
  StorageHelperTest.class,
  InstanceRelationshipsTest.class,
  ReferenceTablesTest.class,
  ItemDamagedStatusAPITest.class,
  ItemDamagedStatusAPIUnitTest.class,
  ItemEffectiveLocationTest.class,
  HridSettingsStorageTest.class,
  HridSettingsStorageParameterizedTest.class,
  ItemCopyNumberMigrationScriptTest.class,
  ItemEffectiveCallNumberComponentsTest.class,
  ItemEffectiveCallNumberDataUpgradeTest.class,
  ModesOfIssuanceMigrationScriptTest.class,
  PrecedingSucceedingTitleTest.class,
  AbstractInstanceRecordsAPITest.class,
  OaiPmhViewTest.class,
  InventoryHierarchyViewTest.class,
  HoldingsSourceTest.class,
  InstanceDomainEventTest.class,
  InventoryViewTest.class,
  InstanceSetTest.class,
  BoundWithStorageTest.class,
  ReindexJobRunnerTest.class,
  EffectiveLocationMigrationTest.class,
  PreviouslyHeldDataUpgradeTest.class,
  ItemShelvingOrderMigrationServiceApiTest.class,
  NotificationSendingErrorRepositoryTest.class,
  LegacyItemEffectiveLocationMigrationScriptTest.class,
  IterationJobRunnerTest.class,
  AuthorityStorageTest.class,
  SampleDataTest.class,
  AuthoritySourceFileAPITest.class,
  AuditDeleteTest.class,
  HridSettingsIncreaseMaxValueMigrationTest.class,
  IllMigrationScriptTest.class,
  InstanceDiscoverySuppressMigrationScriptTest.class,
  InstanceFormatUpgradeTest.class,
  PrecedingSucceedingTitleMigrationScriptTest.class,
  RetainLeadingZeroesMigrationScriptTest.class,
  StatisticalCodeTest.class,
  UpcIsmnMigrationScriptTest.class,

  // These fail.
  //ReferenceTablesTest.class,
  //UpdateItemStatusDateFunctionMigrationTest.class,
})
public class StorageTestSuite {
  private static final Logger logger = LogManager.getLogger();

  private StorageTestSuite() {
    throw new UnsupportedOperationException("Cannot instantiate utility class.");
  }

  @SneakyThrows
  @BeforeClass
  public static void before() {
    logger.info("starting @BeforeClass before()");

    // tests expect English error messages only, no Danish/German/...
    Locale.setDefault(Locale.US);

    PostgresClient.setPostgresTester(new PostgresTesterContainer());
    startKafka();
    startVertx(TENANT_ID);

    logger.info("finished @BeforeClass before()");
  }

  @AfterClass
  public static void after()
      throws InterruptedException,
      ExecutionException,
      TimeoutException {

    logger.info("starting @AfterClass after()");

    removeTenant(TENANT_ID);
    stopVertx();
    stopKafka();

    PostgresClient.stopPostgresTester();
    logger.info("finished @AfterClass after()");
  }

  static void deleteAll(URL rootUrl) {
    HttpClient client = new HttpClient(getVertx());

    CompletableFuture<Response> deleteAllFinished = new CompletableFuture<>();

    try {
      client.delete(rootUrl + "?query=cql.allRecords=1", TENANT_ID,
        ResponseHandler.any(deleteAllFinished));

      Response response = TestBase.get(deleteAllFinished);

      if (response.getStatusCode() != 204) {
        Assert.fail("Delete all preparation failed: " +
          response.getBody());
      }
    } catch (Exception e) {
      throw new RuntimeException("WARNING!!!!! Unable to delete all: " + e.getMessage(), e);
    }
  }

  static void checkForMismatchedIDs(String table) {
    try {
      RowSet<Row> results = getRecordsWithUnmatchedIds(
        TENANT_ID, table);

      Integer mismatchedRowCount = results.rowCount();

      assertThat(mismatchedRowCount, is(0));
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      throw new RuntimeException("WARNING!!!!! Unable to determine mismatched ID rows" + e.getMessage(), e);
    }
  }

  protected static Boolean deleteAll(String tenantId, String tableName) {
    CompletableFuture<Boolean> cf = new CompletableFuture<>();

    try {
      PostgresClient postgresClient = PostgresClient.getInstance(getVertx(), tenantId);

      Promise<RowSet<Row>> promise = Promise.promise();
      String sql = String.format("DELETE FROM %s_%s.%s", tenantId, "mod_inventory_storage", tableName);
      postgresClient.execute(sql, promise);

      promise.future()
        .map(deleteResult -> cf.complete(deleteResult.rowCount() >= 0))
        .otherwise(error -> cf.complete(false));

      return TestBase.get(cf);
    } catch (Exception e) {
      throw new RuntimeException("WARNING!!!!! Unable to delete all: " + e.getMessage(), e);
    }
  }

  private static RowSet<Row> getRecordsWithUnmatchedIds(String tenantId, String tableName){
    PostgresClient dbClient = PostgresClient.getInstance(
      getVertx(), tenantId);

    CompletableFuture<RowSet<Row>> selectCompleted = new CompletableFuture<>();

    String sql = String.format("SELECT null FROM %s_%s.%s" +
        " WHERE CAST(id AS VARCHAR(50)) != jsonb->>'id'",
      tenantId, "mod_inventory_storage", tableName);

    dbClient.select(sql, result -> {
      if (result.succeeded()) {
        selectCompleted.complete(result.result());
      } else {
        selectCompleted.completeExceptionally(result.cause());
      }
    });

    return TestBase.get(selectCompleted);
  }
}
