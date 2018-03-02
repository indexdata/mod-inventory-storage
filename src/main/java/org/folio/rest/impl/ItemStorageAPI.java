package org.folio.rest.impl;

import io.vertx.core.*;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.model.*;
import org.folio.rest.jaxrs.resource.ItemStorageResource;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PgExceptionUtil;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.utils.OutStream;
import org.folio.rest.tools.utils.TenantTool;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;
import org.z3950.zing.cql.cql2pgjson.FieldException;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ItemStorageAPI implements ItemStorageResource {

  static final String ITEM_TABLE = "item";
  private static final String ITEM_MATERIALTYPE_VIEW = "items_mt_view";

  // Has to be lowercase because raml-module-builder uses case sensitive headers
  private static final String TENANT_HEADER = "x-okapi-tenant";
  private static final Logger log = LoggerFactory.getLogger(ItemStorageAPI.class);

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

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      vertxContext.runOnContext(v -> {
        try {
          PostgresClient postgresClient = PostgresClient.getInstance(
            vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

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
    Context vertxContext)
    throws Exception {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient =
        PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      if(entity.getId() == null) {
        entity.setId(UUID.randomUUID().toString());
      }

      vertxContext.runOnContext(v -> {
        try {
          /**This should be replaced with a foreign key / cache since a lookup into the MT table
           * every time an item is inserted is wasteful and slows down the insert process */
          getMaterialType(vertxContext.owner(), tenantId, entity, replyHandler -> {
              int res = replyHandler.result();
              if(res == 0){
                String message = "Can not add " + entity.getMaterialTypeId() + ". Material type not found";
                log.error(message);
                asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(ItemStorageResource.PostItemStorageItemsResponse
                  .withPlainBadRequest(message)));
                return;
              }
              else if(res == -1){
                asyncResultHandler.handle(Future.succeededFuture(
                  ItemStorageResource.PostItemStorageItemsResponse
                    .withPlainInternalServerError("")));
                return;
              }
              else{
                Future<Shelflocation> temporaryLocationFuture;

                if(entity.getTemporaryLocationId() != null) {
                  temporaryLocationFuture = getShelfLocation(vertxContext.owner(), tenantId,
                          entity.getTemporaryLocationId());
                } else {
                  temporaryLocationFuture = Future.succeededFuture();
                }

                temporaryLocationFuture.setHandler(compRes -> {
                  if(compRes.failed()) {
                    String message = "Attempting to specify non-existent location";
                    log.error(message);
                    asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(ItemStorageResource.PostItemStorageItemsResponse
                  .withPlainBadRequest(message)));
                  } else {
                    try {
                      postgresClient.save("item", entity.getId(), entity,
                        reply -> {
                          try {
                            if(reply.succeeded()) {
                              OutStream stream = new OutStream();
                              stream.setData(entity);

                              asyncResultHandler.handle(
                                Future.succeededFuture(
                                  ItemStorageResource.PostItemStorageItemsResponse
                                    .withJsonCreated(reply.result(), stream)));
                            }
                            else {
                              String message = PgExceptionUtil.badRequestMessage(reply.cause());
                              if (message != null) {
                                asyncResultHandler.handle(
                                    Future.succeededFuture(
                                      ItemStorageResource.PostItemStorageItemsResponse
                                        .withPlainBadRequest(message)));
                              } else {
                                asyncResultHandler.handle(
                                  Future.succeededFuture(
                                    ItemStorageResource.PostItemStorageItemsResponse
                                      .withPlainInternalServerError(
                                        reply.cause().getMessage())));
                              }
                            }
                          } catch (Exception e) {
                            asyncResultHandler.handle(
                              Future.succeededFuture(
                                ItemStorageResource.PostItemStorageItemsResponse
                                  .withPlainInternalServerError(e.getMessage())));
                          }
                        });
                    } catch (Exception e) {
                      asyncResultHandler.handle(Future.succeededFuture(
                        ItemStorageResource.PostItemStorageItemsResponse
                          .withPlainInternalServerError(e.getMessage())));
                    }
                  }
                });
              }
          });
        } catch (Exception e) {
          asyncResultHandler.handle(Future.succeededFuture(
            ItemStorageResource.PostItemStorageItemsResponse
              .withPlainInternalServerError(e.getMessage())));
        }
      });
      }
      catch (Exception e) {
        asyncResultHandler.handle(Future.succeededFuture(
          ItemStorageResource.PostItemStorageItemsResponse
            .withPlainInternalServerError(e.getMessage())));
      }
  }
  @Validate
  @Override
  public void getItemStorageItemsByItemId(
    @PathParam("itemId") @NotNull String itemId,
    @QueryParam("lang") @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
    java.util.Map<String, String> okapiHeaders,
    io.vertx.core.Handler<io.vertx.core.AsyncResult<Response>> asyncResultHandler,
    Context vertxContext)
    throws Exception {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient = PostgresClient.getInstance(
        vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      String[] fieldList = {"*"};

      String query = String.format("id==%s", itemId);

      CQLWrapper cql = getCQL(query, 1, 0);

      log.info(String.format("SQL generated from CQL: %s", cql.toString()));

      vertxContext.runOnContext(v -> {
        try {
          postgresClient.get(getTableName(query), Item.class, fieldList, cql, true, false,
            reply -> {
              try {
                if(reply.succeeded()) {
                  List<Item> itemList = (List<Item>) reply.result().getResults();
                  if (itemList.size() == 1) {
                    Item item = itemList.get(0);

                    asyncResultHandler.handle(
                      Future.succeededFuture(
                        ItemStorageResource.GetItemStorageItemsByItemIdResponse.
                          withJsonOK(item)));
                  } else {
                    asyncResultHandler.handle(
                      Future.succeededFuture(
                        ItemStorageResource.GetItemStorageItemsByItemIdResponse.
                          withPlainNotFound("Not Found")));
                  }
                }
                else {
                  Future.succeededFuture(
                    ItemStorageResource.GetItemStorageItemsByItemIdResponse
                      .withPlainInternalServerError(
                        reply.cause().getMessage()));
                }
              } catch (Exception e) {
                asyncResultHandler.handle(Future.succeededFuture(
                  ItemStorageResource.GetItemStorageItemsByItemIdResponse.
                    withPlainInternalServerError(e.getMessage())));
              }
            });
        } catch (Exception e) {
          asyncResultHandler.handle(Future.succeededFuture(
            ItemStorageResource.GetItemStorageItemsByItemIdResponse.
              withPlainInternalServerError(e.getMessage())));
        }
      });
    } catch (Exception e) {
      asyncResultHandler.handle(Future.succeededFuture(
        ItemStorageResource.GetItemStorageItemsByItemIdResponse.
          withPlainInternalServerError(e.getMessage())));
    }
  }
  @Validate
  @Override
  public void deleteItemStorageItems(
    @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
    throws Exception {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      vertxContext.runOnContext(v -> {
        PostgresClient postgresClient = PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

        postgresClient.mutate(String.format("TRUNCATE TABLE %s_%s.item",
          tenantId, "mod_inventory_storage"),
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
      });
    }
    catch(Exception e) {
      asyncResultHandler.handle(Future.succeededFuture(
        ItemStorageResource.DeleteItemStorageItemsResponse.
          withPlainInternalServerError(e.getMessage())));
    }
  }

  @Validate
  @Override
  public void putItemStorageItemsByItemId(
    @PathParam("itemId") @NotNull String itemId,
    @QueryParam("lang") @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
    Item entity,
    java.util.Map<String, String> okapiHeaders,
    io.vertx.core.Handler<io.vertx.core.AsyncResult<Response>> asyncResultHandler,
    Context vertxContext)
    throws Exception {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient =
        PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      vertxContext.runOnContext(v -> {
        try {
          getMaterialType(vertxContext.owner(), tenantId, entity, replyHandler -> {
              int res = replyHandler.result();
              if(res == 0) {
                String message = "Can not add " + entity.getMaterialTypeId() + ". Material type not found";
                log.error(message);
                asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(PutItemStorageItemsByItemIdResponse
                  .withPlainBadRequest(message)));
              }
              else if(res == -1){
                asyncResultHandler.handle(Future.succeededFuture(
                  ItemStorageResource.PostItemStorageItemsResponse
                    .withPlainInternalServerError("")));
              }
              else {
                try {
                  String[] fieldList = {"*"};

                  String query = String.format("id==%s", itemId);

                  CQLWrapper cql = getCQL(query, 1, 0);

                  log.info(String.format("SQL generated from CQL: %s", cql.toString()));

                  postgresClient.get(getTableName(query), Item.class, fieldList, cql, true, false,
                    reply -> {
                      if(reply.succeeded()) {
                        List<Item> itemList = (List<Item>) reply.result().getResults();
                        if (itemList.size() == 1) {
                          try {
                            postgresClient.update("item", entity, entity.getId(),
                              update -> {
                                try {
                                  if (update.succeeded()) {
                                    OutStream stream = new OutStream();
                                    stream.setData(entity);

                                    asyncResultHandler.handle(
                                      Future.succeededFuture(
                                        PutItemStorageItemsByItemIdResponse
                                          .withNoContent()));
                                  } else {
                                    String message = PgExceptionUtil.badRequestMessage(update.cause());
                                    if (message != null) {
                                      asyncResultHandler.handle(
                                          Future.succeededFuture(
                                            PutItemStorageItemsByItemIdResponse
                                              .withPlainBadRequest(message)));
                                    } else {
                                      asyncResultHandler.handle(
                                        Future.succeededFuture(
                                          PutItemStorageItemsByItemIdResponse
                                            .withPlainInternalServerError(
                                              update.cause().getMessage())));
                                    }
                                  }
                                } catch (Exception e) {
                                  asyncResultHandler.handle(
                                    Future.succeededFuture(
                                      PostItemStorageItemsResponse
                                        .withPlainInternalServerError(e.getMessage())));
                                }
                              });
                          } catch (Exception e) {
                            asyncResultHandler.handle(Future.succeededFuture(
                              PutItemStorageItemsByItemIdResponse
                                .withPlainInternalServerError(e.getMessage())));
                          }
                        } else {
                          try {
                            postgresClient.save("item", entity.getId(), entity,
                              save -> {
                                try {
                                  if (save.succeeded()) {
                                    OutStream stream = new OutStream();
                                    stream.setData(entity);

                                    asyncResultHandler.handle(
                                      Future.succeededFuture(
                                        PutItemStorageItemsByItemIdResponse
                                          .withNoContent()));
                                  } else {
                                    asyncResultHandler.handle(
                                      Future.succeededFuture(
                                        PutItemStorageItemsByItemIdResponse
                                          .withPlainInternalServerError(
                                            save.cause().getMessage())));
                                  }

                                } catch (Exception e) {
                                  asyncResultHandler.handle(
                                    Future.succeededFuture(
                                      PostItemStorageItemsResponse
                                        .withPlainInternalServerError(e.getMessage())));
                                }
                              });
                          } catch (Exception e) {
                            asyncResultHandler.handle(Future.succeededFuture(
                              PutItemStorageItemsByItemIdResponse
                                .withPlainInternalServerError(e.getMessage())));
                          }
                        }
                      } else {
                        asyncResultHandler.handle(Future.succeededFuture(
                          PutItemStorageItemsByItemIdResponse
                            .withPlainInternalServerError(reply.cause().getMessage())));
                      }
                    });
                } catch (Exception e) {
                  asyncResultHandler.handle(Future.succeededFuture(
                    ItemStorageResource.PostItemStorageItemsResponse
                      .withPlainInternalServerError(e.getMessage())));
                }
              }
          });
        } catch (Exception e) {
          asyncResultHandler.handle(Future.succeededFuture(
            ItemStorageResource.PostItemStorageItemsResponse
              .withPlainInternalServerError(e.getMessage())));
        }
      });
    } catch (Exception e) {
      asyncResultHandler.handle(Future.succeededFuture(
        ItemStorageResource.PostItemStorageItemsResponse
          .withPlainInternalServerError(e.getMessage())));
    }
  }

  @Validate
  @Override
  public void deleteItemStorageItemsByItemId(
    @PathParam("itemId") @NotNull String itemId,
    @QueryParam("lang") @DefaultValue("en") @Pattern(regexp = "[a-zA-Z]{2}") String lang,
    java.util.Map<String, String> okapiHeaders,
    io.vertx.core.Handler<io.vertx.core.AsyncResult<Response>> asyncResultHandler,
    Context vertxContext)
    throws Exception {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient =
        PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      Criteria a = new Criteria();

      a.addField("'id'");
      a.setOperation("=");
      a.setValue(itemId);

      Criterion criterion = new Criterion(a);

      vertxContext.runOnContext(v -> {
        try {
          postgresClient.delete("item", criterion,
            reply -> {
              if(reply.succeeded()) {
                asyncResultHandler.handle(
                  Future.succeededFuture(
                    DeleteItemStorageItemsByItemIdResponse
                      .withNoContent()));
              }
              else {
                asyncResultHandler.handle(Future.succeededFuture(
                  ItemStorageResource.DeleteItemStorageItemsByItemIdResponse
                    .withPlainInternalServerError("Error")));
              }
            });
        } catch (Exception e) {
          asyncResultHandler.handle(Future.succeededFuture(
            ItemStorageResource.DeleteItemStorageItemsByItemIdResponse
              .withPlainInternalServerError("Error")));
        }
      });
    } catch (Exception e) {
      asyncResultHandler.handle(Future.succeededFuture(
        ItemStorageResource.DeleteItemStorageItemsByItemIdResponse
          .withPlainInternalServerError("Error")));
    }
  }

  /**
   *
   * @param vertx
   * @param tenantId
   * @param item
   * @param handler
   * @throws Exception
   */
  private void getMaterialType(
    Vertx vertx,
    String tenantId,
    Item item,
    Handler<AsyncResult<Integer>> handler) throws Exception{

    Mtype mtype = new Mtype();

    String mtID = item.getMaterialTypeId();

    if(mtID == null) {
      //allow null material types so that they can be added after a record is created
      handler.handle(io.vertx.core.Future.succeededFuture(1));
    } else {
      mtype.setId(mtID);
      /** check if the material type exists, if not, can not add the item **/
      PostgresClient.getInstance(vertx, tenantId).get(
        MaterialTypeAPI.MATERIAL_TYPE_TABLE, mtype, new String[]{"_id"}, true, false, 0, 1, check -> {
          if(check.succeeded()) {
            List<Mtype> mtypeList0 = (List<Mtype>) check.result().getResults();
            if(mtypeList0.size() == 0){
              handler.handle(io.vertx.core.Future.succeededFuture(0));
            }
            else {
              handler.handle(io.vertx.core.Future.succeededFuture(1));
            }
          }
          else {
            log.error(check.cause().getLocalizedMessage(), check.cause());
            handler.handle(io.vertx.core.Future.succeededFuture(-1));
          }
        });
    }
  }

  private Future<Shelflocation> getShelfLocation(
    Vertx vertx,
    String tenantId,
    String locationId
  ) {
    Future<Shelflocation> future = Future.future();
    try {
      Criteria crit = new Criteria(ShelfLocationAPI.SHELF_LOCATION_SCHEMA_PATH);
      crit.addField(ShelfLocationAPI.ID_FIELD_NAME);
      crit.setOperation("=");
      crit.setValue(locationId);
      PostgresClient.getInstance(vertx, tenantId).get(ShelfLocationAPI.SHELF_LOCATION_TABLE,
              Shelflocation.class, new Criterion(crit), true, false, getReply -> {
        if(getReply.failed()) {
          future.fail(getReply.cause());
        } else {
          List<Shelflocation> locationList = (List<Shelflocation>)getReply.result().getResults();
          if(locationList.size() < 1) {
            future.fail("No location found");
          } else {
            future.complete(locationList.get(0));
          }
        }
      });
    } catch(Exception e) {
      future.fail(e);
    }
    return future;
  }
}
