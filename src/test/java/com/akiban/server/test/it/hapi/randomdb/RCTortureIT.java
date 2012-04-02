/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.test.it.hapi.randomdb;

import com.akiban.server.api.DDLFunctions;
import com.akiban.server.api.HapiGetRequest;
import com.akiban.server.api.HapiPredicate;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.error.InvalidOperationException;
import com.akiban.server.service.memcache.outputter.jsonoutputter.JsonOutputter;
import com.akiban.server.service.session.Session;
import com.akiban.server.store.Store;
import com.akiban.server.test.it.ITBase;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

// To run this test manually, set -DdebugMode=true. This will continue past test failures, print database contents,
// print each query, and for expected != actual, print both, nicely formatted. To investigate a single

public class RCTortureIT extends ITBase
{
    @Before
    public void setUp() {
        actual = new Actual(this, configService(), dxl());
    }

    @Test
    public void testRepeatedly() throws Exception
    {
        for (trial = 0; trial < TRIALS; trial++) {
            if (DEBUG_TRIAL < 0 || trial == DEBUG_TRIAL) {
                test();
                dropAllTables();
            }
        }
    }

    Store getStore() {
        return store();
    }

    Session testSession()
    {
        return session();
    }

    String table(int type)
    {
        return tableName(type).getTableName();
    }

    HKey hKey(NewRow row)
    {
        return new HKey(this, row);
    }

    void sort(List<NewRow> rows)
    {
        Collections.sort(rows, ROW_COMPARATOR);
    }

    DDLFunctions ddlFunctions()
    {
        return ddl();
    }

    int table(String schema, String table, String... definitions) throws InvalidOperationException
    {
        return super.createTable(schema, table, definitions);
    }

    void index(String schema, String tableName, String indexName, String... indexCols) {
        super.createIndex(schema, tableName, indexName, indexCols);
    }

    void addRow(NewRow row) throws Exception
    {
        dml().writeRow(session(), row);
        db.add(row);
    }

    void printDB()
    {
        if (trial == DEBUG_TRIAL) {
            print("----------------------------------------------------");
            for (NewRow row : db) {
                int tableId = row.getTableId();
                if (tableId == customerTable) {
                    print("customer  %s", row.get(0));
                } else if (tableId == orderTable) {
                    print("order     %s %s", row.get(0), row.get(1));
                } else if (tableId == itemTable) {
                    print("item      %s %s %s", row.get(0), row.get(1), row.get(2));
                } else if (tableId == addressTable) {
                    print("address   %s %s", row.get(0), row.get(1));
                }
            }
            print("----------------------------------------------------");
        }
    }

    void print(String template, Object... args)
    {
        if (trial == DEBUG_TRIAL) {
            System.out.println(String.format(template, args));
        }
    }

    private void test() throws Exception
    {
        System.out.println(String.format("TRIAL %s", trial));
        random = new Random(seed(trial));
        database.createSchema();
        database.populate();
        runQueries();
    }

    private void runQueries() throws Exception
    {
        if (DEBUG_TRIAL >= 0) {
            runQuery(customerTable, itemTable, Column.I_CID, HapiPredicate.Operator.EQ, 1, false);
        } else {
            for (HapiPredicate.Operator comparison : HapiPredicate.Operator.values()) {
                if (comparison != HapiPredicate.Operator.NE) {
                    for (int cid = 0; cid < MAX_CUSTOMERS; cid++) {
                        // One-table queries, e.g. schema:customer:cid>4
                        runQuery(customerTable, customerTable, Column.C_CID, comparison, cid, false);
                        runQuery(customerTable, customerTable, Column.C_CID_COPY, comparison, cid, true);
                        runQuery(orderTable, orderTable, Column.O_CID, comparison, cid, false);
                        runQuery(orderTable, orderTable, Column.O_CID_COPY, comparison, cid, true);
                        runQuery(itemTable, itemTable, Column.I_CID, comparison, cid, false);
                        runQuery(itemTable, itemTable, Column.I_CID_COPY, comparison, cid, true);
                        runQuery(addressTable, addressTable, Column.A_CID, comparison, cid, false);
                        runQuery(addressTable, addressTable, Column.A_CID_COPY, comparison, cid, true);
                        // Two-table queries, e.g. schema:customer:(order)oid=3
                        runQuery(customerTable, orderTable, Column.O_CID, comparison, cid, false);
                        runQuery(customerTable, orderTable, Column.O_CID_COPY, comparison, cid, true);
                        runQuery(customerTable, itemTable, Column.I_CID, comparison, cid, false);
                        runQuery(customerTable, itemTable, Column.I_CID_COPY, comparison, cid, true);
                        runQuery(orderTable, itemTable, Column.I_CID, comparison, cid, false);
                        runQuery(orderTable, itemTable, Column.I_CID_COPY, comparison, cid, true);
                        runQuery(customerTable, addressTable, Column.A_CID, comparison, cid, false);
                        runQuery(customerTable, addressTable, Column.A_CID_COPY, comparison, cid, true);
                    }
                }
            }
            // TODO: Query for oid, iid.
        }
    }

    private void runQuery(int rootTable,
                          int predicateTable,
                          Column predicateColumn,
                          HapiPredicate.Operator comparison,
                          int literal,
                          boolean indexOrdered) throws Exception
    {
        String actualQueryResult =
            actual.queryResult(rootTable, predicateTable, predicateColumn, comparison, literal);
        String expectedQueryResult =
            expected.queryResult(rootTable, predicateTable, predicateColumn, comparison, literal, indexOrdered);
        if (trial == DEBUG_TRIAL) {
            if (!actualQueryResult.equals(expectedQueryResult)) {
                print("expected:\n%s", formatJSON(expectedQueryResult));
                print("actual:\n%s", formatJSON(actualQueryResult));
            }
        } else {
            assertEquals(expectedQueryResult, actualQueryResult);
        }
    }

    private String formatJSON(String json) throws JSONException
    {
        return new JSONObject(json).toString(4);
    }

    private static int seed(int trial)
    {
        return SEED + trial * 9987001;
    }

    Comparator<NewRow> ROW_COMPARATOR = new Comparator<NewRow>()
    {
        @Override
        public int compare(NewRow x, NewRow y)
        {
            return hKey(x).compareTo(hKey(y));
        }
    };

    // Test parameters
    static final int TRIALS = 10;
    static final int SEED = 123456789;
    static final int MAX_CUSTOMERS = 3;
    static final int MAX_ORDERS_PER_CUSTOMER = 3;
    static final int MAX_ITEMS_PER_ORDER = 2;
    static final int MAX_ADDRESSES_PER_CUSTOMER = 2;
    static final Integer DEBUG_TRIAL = Integer.getInteger("debugTrial", -1);

    // Constants
    static final String SCHEMA = "schema";
    static final String COLON = ":";
    static final String OPEN = "(";
    static final String CLOSE = ")";

    // Test state
    Random random;
    int trial;
    int customerTable;
    int orderTable;
    int itemTable;
    int addressTable;
    List<NewRow> db = new ArrayList<NewRow>();
    final JsonOutputter outputter = new JsonOutputter();
    String query;
    HapiGetRequest request;
    private Expected expected = new Expected(this);
    private Actual actual;
    private Database database = new Database(this);
}
