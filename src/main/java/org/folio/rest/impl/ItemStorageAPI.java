package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.model.Item;
import org.folio.rest.jaxrs.model.Items;
import org.folio.rest.jaxrs.model.Status;
import org.folio.rest.jaxrs.resource.ItemStorageResource;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.utils.TenantTool;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;
import org.z3950.zing.cql.cql2pgjson.FieldException;

public class ItemStorageAPI implements ItemStorageResource {

  static final String ITEM_TABLE = "item";
  private static final String ITEM_MATERIALTYPE_VIEW = "items_mt_view";

  private static final Logger log = LoggerFactory.getLogger(ItemStorageAPI.class);
  private static final String DEFAULT_STATUS_NAME = "Available";

  private String convertQuery(String cql){
    if(cql != null){
      return cql.replaceAll("(?i)materialTypeId\\.|(?i)materialType\\.", ITEM_MATERIALTYPE_VIEW+".mt_jsonb.");
    }
    return cql;
  }

  /**
   * right now, just query the join view if a cql was passed in, otherwise work with the
   * master items table. This can be optimized in the future to check if there is really a need
   * to use the join view due to cross table cqling - like returning items sorted by material type
   * @param cql
   * @return
   */
  private String getTableName(String cql) {
    if(cql != null){
      return ITEM_MATERIALTYPE_VIEW;
    }
    return ITEM_TABLE;
  }

  /**
   * additional querying across tables should add the field to the end of the Arrays.asList
   * and then add a replace to the convertQuery function
   * @param query
   * @param limit
   * @param offset
   * @return
   * @throws FieldException
   */
  private CQLWrapper getCQL(String query, int limit, int offset) throws FieldException {
    CQL2PgJSON cql2pgJson = new CQL2PgJSON(Arrays.asList(ITEM_MATERIALTYPE_VIEW+".jsonb",ITEM_MATERIALTYPE_VIEW+".mt_jsonb"));
    return new CQLWrapper(cql2pgJson, convertQuery(query)).setLimit(new Limit(limit)).setOffset(new Offset(offset));
  }

  @Validate
  @Override
  public void getItemStorageItems(
    int offset,
    int limit,
    String query,
    String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext)
    throws Exception {

    try {
      vertxContext.runOnContext(v -> {
        try {
          PostgresClient postgresClient = StorageHelper.postgresClient(vertxContext, okapiHeaders);

          String[] fieldList = {"*"};

          CQLWrapper cql = getCQL(query,limit,offset);

          log.info(String.format("SQL generated by CQL query (%s): %s", query, cql.toString()));

          postgresClient.get(getTableName(query), Item.class, fieldList, cql, true, false,
            reply -> {
              try {

                if(reply.succeeded()) {
                  List<Item> items = (List<Item>) reply.result().getResults();

                  Items itemList = new Items();
                  itemList.setItems(items);
                  itemList.setTotalRecords(reply.result().getResultInfo().getTotalRecords());

                  asyncResultHandler.handle(Future.succeededFuture(
                    ItemStorageResource.GetItemStorageItemsResponse.
                      withJsonOK(itemList)));
                }
                else {
                  asyncResultHandler.handle(Future.succeededFuture(
                    ItemStorageResource.GetItemStorageItemsResponse.
                      withPlainInternalServerError(reply.cause().getMessage())));
                }
              } catch (Exception e) {
                if(e.getCause() != null && e.getCause().getClass().getSimpleName().contains("CQLParseException")) {
                  asyncResultHandler.handle(Future.succeededFuture(
                    GetItemStorageItemsResponse.withPlainBadRequest(
                      "CQL Parsing Error for '" + query + "': " + e.getLocalizedMessage())));
                }
                else {
                  asyncResultHandler.handle(Future.succeededFuture(
                    ItemStorageResource.GetItemStorageItemsResponse.
                      withPlainInternalServerError("Error")));
                }
              }
            });
        }
        catch (IllegalStateException e) {
          asyncResultHandler.handle(Future.succeededFuture(
            GetItemStorageItemsResponse.withPlainInternalServerError(
              "CQL State Error for '" + query + "': " + e.getLocalizedMessage())));
        }
        catch (Exception e) {
          if(e.getCause() != null && e.getCause().getClass().getSimpleName().contains("CQLParseException")) {
            asyncResultHandler.handle(Future.succeededFuture(
              GetItemStorageItemsResponse.withPlainBadRequest(
              "CQL Parsing Error for '" + query + "': " + e.getLocalizedMessage())));
          } else {
            asyncResultHandler.handle(Future.succeededFuture(
              ItemStorageResource.GetItemStorageItemsResponse.
                withPlainInternalServerError("Error")));
          }
        }
      });
    } catch (Exception e) {
      if(e.getCause() != null && e.getCause().getClass().getSimpleName().contains("CQLParseException")) {
        asyncResultHandler.handle(Future.succeededFuture(
          GetItemStorageItemsResponse.withPlainBadRequest(
            "CQL Parsing Error for '" + query + "': " + e.getLocalizedMessage())));
      } else {
        asyncResultHandler.handle(Future.succeededFuture(
          ItemStorageResource.GetItemStorageItemsResponse.
            withPlainInternalServerError("Error")));
      }
    }
  }

  @Validate
  @Override
  public void postItemStorageItems(
      @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
      Item entity,
      Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {

    if (entity.getStatus() == null) {
      entity.setStatus(new Status().withName(DEFAULT_STATUS_NAME));
    }
    StorageHelper.post(ITEM_TABLE, entity, okapiHeaders, vertxContext, asyncResultHandler,
        PostItemStorageItemsResponse::withJsonCreated,
        PostItemStorageItemsResponse::withPlainBadRequest,
        PostItemStorageItemsResponse::withPlainInternalServerError);
  }

  @Validate
  @Override
  public void getItemStorageItemsByItemId(
      @PathParam("itemId") @NotNull String itemId,
      @QueryParam("lang") @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
      java.util.Map<String, String> okapiHeaders,
      io.vertx.core.Handler<io.vertx.core.AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {

    StorageHelper.getById(ITEM_TABLE, Item.class, itemId, okapiHeaders, vertxContext, asyncResultHandler,
        GetItemStorageItemsByItemIdResponse::withJsonOK,
        GetItemStorageItemsByItemIdResponse::withPlainNotFound,
        GetItemStorageItemsByItemIdResponse::withPlainInternalServerError);
  }

  @Validate
  @Override
  public void deleteItemStorageItems(
      @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
      Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
          throws Exception {

    String tenantId = TenantTool.tenantId(okapiHeaders);
    PostgresClient postgresClient = StorageHelper.postgresClient(vertxContext, okapiHeaders);

    postgresClient.mutate(String.format("TRUNCATE TABLE %s_%s.item", tenantId, "mod_inventory_storage"),
        reply -> {
          if (reply.succeeded()) {
            asyncResultHandler.handle(Future.succeededFuture(
                ItemStorageResource.DeleteItemStorageItemsResponse.noContent()
                .build()));
          } else {
            asyncResultHandler.handle(Future.succeededFuture(
                ItemStorageResource.DeleteItemStorageItemsResponse.
                withPlainInternalServerError(reply.cause().getMessage())));
          }
        });
  }

  @Validate
  @Override
  public void putItemStorageItemsByItemId(
      @PathParam("itemId") @NotNull String itemId,
      @QueryParam("lang") @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
      Item entity,
      java.util.Map<String, String> okapiHeaders,
      Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {

    StorageHelper.put(ITEM_TABLE, entity, itemId, okapiHeaders, vertxContext, asyncResultHandler,
        PutItemStorageItemsByItemIdResponse::withNoContent,
        PutItemStorageItemsByItemIdResponse::withPlainBadRequest,
        PutItemStorageItemsByItemIdResponse::withPlainInternalServerError);
  }

  @Validate
  @Override
  public void deleteItemStorageItemsByItemId(
      @PathParam("itemId") @NotNull String itemId,
      @QueryParam("lang") @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
      java.util.Map<String, String> okapiHeaders,
      io.vertx.core.Handler<io.vertx.core.AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) {

    StorageHelper.deleteById(ITEM_TABLE, itemId, okapiHeaders, vertxContext, asyncResultHandler,
        DeleteItemStorageItemsByItemIdResponse::withNoContent,
        DeleteItemStorageItemsByItemIdResponse::withPlainInternalServerError);
  }
}
