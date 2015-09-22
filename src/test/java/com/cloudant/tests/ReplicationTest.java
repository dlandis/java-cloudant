/*
 * Copyright (C) 2011 lightcouch.org
 * Copyright (c) 2015 IBM Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.cloudant.tests;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.cloudant.client.api.CloudantClient;
import com.cloudant.client.api.Database;
import com.cloudant.client.api.model.ReplicationResult;
import com.cloudant.client.api.model.ReplicationResult.ReplicationHistory;
import com.cloudant.client.api.views.Key;
import com.cloudant.client.api.views.ViewResponse;
import com.cloudant.test.main.RequiresDB;
import com.cloudant.tests.util.Utils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Category(RequiresDB.class)
public class ReplicationTest {
    private static final Log log = LogFactory.getLog(ReplicationTest.class);

    private static Properties props;
    private static Database db1;

    private static Database db2;

    private static String db1URI;
    private static String db2URI;
    private CloudantClient account;

    @Before
    public void setUp() {
        account = CloudantClientHelper.getClient();

        db1 = account.database("lightcouch-db-test", true);
        db1URI = CloudantClientHelper.SERVER_URI.toString() + "/lightcouch-db-test";


        db2 = account.database("lightcouch-db-test-2", true);

        db1.syncDesignDocsWithDb();
        db2.syncDesignDocsWithDb();

        db2URI = CloudantClientHelper.SERVER_URI.toString() + "/lightcouch-db-test-2";
    }

    @After
    public void tearDown() {
        account.deleteDB("lightcouch-db-test");
        account.deleteDB("lightcouch-db-test-2");
        account.shutdown();
    }

    @Test
    public void replication() {
        ReplicationResult result = account.replication()
                .createTarget(true)
                .source(db1URI)
                .target(db2URI)
                .trigger();

        List<ReplicationHistory> histories = result.getHistories();
        assertThat(histories.size(), not(0));
    }

    @Test
    public void replication_filteredWithQueryParams() {
        Map<String, Object> queryParams = new HashMap<String, Object>();
        queryParams.put("somekey1", "value 1");

        account.replication()
                .createTarget(true)
                .source(db1URI)
                .target(db2URI)
                .filter("example/example_filter")
                .queryParams(queryParams)
                .trigger();
    }

    @Test
    public void replicateDatabaseUsingReplicationTrigger() throws Exception {

        String docId = Utils.generateUUID();
        Foo fooOnDb1 = new Foo(docId);
        db1.save(fooOnDb1);

        // trigger a replication
        ReplicationResult result = account.replication().source(db1URI)
                .target(db2URI).createTarget(true).trigger();

        assertTrue("The replication should complete ok", result.isOk());

        Foo fooOnDb2 = Utils.findDocumentWithRetries(db2, docId, Foo.class, 20);
        assertNotNull("The document should have been replicated", fooOnDb2);
    }

    @Test
    public void replication_conflict() throws Exception {
        String docId = Utils.generateUUID();
        Foo foodb1 = new Foo(docId, "title");
        Foo foodb2 = null;

        foodb1 = new Foo(docId, "titleX");

        db1.save(foodb1);

        account.replication().source(db1URI)
                .target(db2URI).trigger();

        foodb2 = Utils.findDocumentWithRetries(db2, docId, Foo.class, 20);
        foodb2.setTitle("titleY");
        db2.update(foodb2);

        foodb1 = Utils.findDocumentWithRetries(db1, docId, Foo.class, 20);
        foodb1.setTitle("titleZ");
        db1.update(foodb1);

        account.replication().source(db1URI)
                .target(db2URI).trigger();

        ViewResponse<Key.ComplexKey, String> conflicts = db2.getViewRequestBuilder
                ("conflicts", "conflict").newRequest(Key.Type.COMPLEX, String.class).build()
                .getResponse();

        assertThat(conflicts.getRows().size(), is(not(0)));
    }
}
