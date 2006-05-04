/*
 
   Derby - Class org.apache.derbyTesting.functionTests.tests.jdbc4.ConnectionTest
 
   Copyright 2006 The Apache Software Foundation or its licensors, as applicable.
 
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
 
      http://www.apache.org/licenses/LICENSE-2.0
 
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 
 */
package org.apache.derbyTesting.functionTests.tests.jdbc4;

import junit.framework.*;

import org.apache.derbyTesting.functionTests.util.BaseJDBCTestCase;
import org.apache.derbyTesting.functionTests.util.SQLStateConstants;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.*;
import java.util.Properties;
import javax.sql.*;

/**
 * Tests for the JDBC 4.0 specific methods in the connection object(s).
 *
 * This class defines all test methods in <code>ConnectionTest</code>, and 
 * overrides <code>setUp</code> in subclasses to run these tests with different
 * types of connection objects. To see what connection types are tested, look
 * at the <code>suite</code> method.
 *
 * In addition, the <code>getName</code> method is overridden in the subclasses
 * to be able to see with what connection type the tests fail/run with.
 */
public class ConnectionTest
    extends BaseJDBCTestCase {

    /** 
     * Default connection used by the tests. 
     * The setup of the connection can be overridden in the <code>setUp</code> 
     * method in the subclasses.
     */
    protected Connection con = null;

    /**
     * Create a test with the given name.
     * 
     * @param name name of the test.
     */
    public ConnectionTest(String name) {
        super(name);
    }
    
    /**
     * Obtain a "regular" connection that the tests can use.
     */
    public void setUp() 
        throws SQLException {
        con = getConnection(); 
    }

    public void tearDown() 
        throws SQLException {
        if (con != null && !con.isClosed()) {
            con.rollback();
            con.close();
        }
        con = null;
    }
   
    //------------------------- T E S T  M E T H O D S ------------------------
    
    public void embeddedCreateBlobNotImplemented()
        throws SQLException {
        try {
            con.createBlob();
            fail("createBlob() should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }
    
    public void embeddedCreateClobNotImplemented()
        throws SQLException {
        try {
            con.createClob();
            fail("createClob() should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }

    public void testCreateArrayNotImplemented()
        throws SQLException {
        try {
            con.createArray(null, null);
            fail("createArray(String,Object[]) should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }

    public void testCreateNClobNotImplemented()
        throws SQLException {
        try {
            con.createNClob();
            fail("createNClob() should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }

    /**
     * Simply test that the method is implemented.
     * If the method actually does what is should, is not yet tested
     * (there are missing implementations).
     */
    public void testCreateQueryObjectIsImplemented()
        throws SQLException {
        con.createQueryObject(TestQuery.class);
    }

    public void testCreateSQLXMLNotImplemented()
        throws SQLException {
        try {
            con.createSQLXML();
            fail("createSQLXML() should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }

    public void testCreateStructNotImplemented()
        throws SQLException {
        try {
            con.createStruct(null, null);
            fail("createStruct(String,Object[]) should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }
    
    public void testGetClientInfoNotImplemented()
        throws SQLException {
        try {
            con.getClientInfo();
            fail("getClientInfo() should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }
    
    public void testGetClientInfoStringNotImplemented()
        throws SQLException {
        try {
            con.getClientInfo(null);
            fail("getClientInfo(String) should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }

    /**
     * Tests that <code>getTypeMap()</code> returns an empty map when
     * no type map has been installed.
     * @exception SQLException if an error occurs
     */
    public void testGetTypeMapReturnsEmptyMap() throws SQLException {
        assertTrue(con.getTypeMap().isEmpty());
    }

    public void testIsWrapperReturnsFalse()
        throws SQLException {
        assertFalse(con.isWrapperFor(ResultSet.class));
    }

    public void testIsWrapperReturnsTrue()
        throws SQLException {
        assertTrue(con.isWrapperFor(Connection.class));
    }

    public void testSetClientInfoPropertiesNotImplemented()
        throws SQLException {
        try {
            con.setClientInfo(new Properties());
            fail("setClientInfo(Properties) should not be implemented");
        } catch (ClientInfoException cie) {
            assertSQLState("Invalid SQL state for unimplemented method",
                           "0A000", // Can this be added to SQLStateConstants?
                           cie); 
        }
    }

    public void testSetClientInfoStringNotImplemented()
        throws SQLException {
        try {
            con.setClientInfo("name", "value");
            fail("setClientInfo(String,String) should not be implemented");
        } catch (SQLFeatureNotSupportedException sfnse) {
            // Do nothing, we are fine
        }
    }
    
    public void testUnwrapValid()
        throws SQLException {
        Connection unwrappedCon = con.unwrap(Connection.class);
    }

    public void testUnwrapInvalid()
        throws SQLException {
        try {
            ResultSet unwrappedRs = con.unwrap(ResultSet.class);
            fail("unwrap should have thrown an exception");
        } catch (SQLException sqle) {
            assertSQLState("Incorrect SQL state when unable to unwrap",
                           SQLStateConstants.UNABLE_TO_UNWRAP,
                           sqle);
        }
    }
        
    //------------------ E N D  O F  T E S T  M E T H O D S -------------------

    /**
     * Create suite containing client-only tests.
     */
    private static TestSuite clientSuite() {
        TestSuite clientSuite = new TestSuite();
        return clientSuite; 
    }
    
    /**
     * Create suite containing embedded-only tests.
     */
    private static TestSuite embeddedSuite() {
        TestSuite embeddedSuite = new TestSuite();
        embeddedSuite.addTest(new ConnectionTest(
                    "embeddedCreateBlobNotImplemented"));
        embeddedSuite.addTest(new ConnectionTest(
                    "embeddedCreateClobNotImplemented"));
        return embeddedSuite;
    }
    
    /**
     * Create a test suite containing tests for various connection types.
     * Three subsuites are created:
     * <ol><li>ConnectionTest suite</li>
     *     <li>PooledConnectionTest suite</li>
     *     <li>XAConnectionTest suite</li>
     *  </ol>
     *
     *  In addition, separate suites for embedded- and client-only are added
     *  to the subsuites when appropriate.
     */
    public static Test suite() {
        TestSuite topSuite = new TestSuite("ConnectionTest top suite");
        // Add suite for "regular" Connection tests.
        TestSuite baseConnSuite = 
            new TestSuite(ConnectionTest.class, "ConnectionTest suite");
        // Add suite for PooledConnection tests.
        TestSuite pooledConnSuite =
            new TestSuite(PooledConnectionTest.class,
                          "PooledConnectionTest suite");
        // Add suite for XAConnection tests.
        TestSuite xaConnSuite =
            new TestSuite(XAConnectionTest.class, "XAConnectionTest suite");
        
        // Add client only tests
        // NOTE: JCC is excluded
        if (usingDerbyNetClient()) {
            TestSuite clientSuite = clientSuite();
            clientSuite.setName("ConnectionTest client-only suite");
            baseConnSuite.addTest(clientSuite);
            clientSuite = clientSuite();
            clientSuite.setName("PooledConnectionTest client-only suite");
            pooledConnSuite.addTest(clientSuite);
            clientSuite = clientSuite();
            clientSuite.setName("XAConnectionTest client-only suite");
            xaConnSuite.addTest(clientSuite);
        }
        // Add embedded only tests
        if (usingEmbedded()) {
            TestSuite embeddedSuite = embeddedSuite();
            embeddedSuite.setName("ConnectionTest embedded-only suite");
            baseConnSuite.addTest(embeddedSuite);
            embeddedSuite = embeddedSuite();
            embeddedSuite.setName("PooledConnectionTest embedded-only suite");
            pooledConnSuite.addTest(embeddedSuite);
            embeddedSuite = embeddedSuite();
            embeddedSuite.setName("XAConnectionTest embedded-only suite");
            xaConnSuite.addTest(embeddedSuite);
        }
        topSuite.addTest(baseConnSuite);
        topSuite.addTest(pooledConnSuite);
        topSuite.addTest(xaConnSuite);
        return topSuite;
    }
    
    /**
     * Tests for the real connection in a <code>PooledConnection</code>.
     *
     * This class subclasses <code>ConnectionTest</code>, and runs the test-
     * methods implemented there. By doing this, we don't have to duplicate
     * the test code. We only run the tests with a different type of 
     * connection.
     */
    public static class PooledConnectionTest 
        extends ConnectionTest {

        /**
         * Create a test with the given name.
         * 
         * @param name name of the test.
         */
        public PooledConnectionTest(String name) {
            super(name);
        }

        /**
         * Return name of the test, with "_POOLED" appended.
         * Must override this method to be able to separate which connection
         * the failure happened with, since the test methods are all in the
         * superclass.
         *
         * @return the name of the test method in <code>ConnectionTest</code>,
         *      appended with the string "_POOLED".
         */
        public String getName() {
            return super.getName() + "_POOLED";
        }

        /**
         * Obtain a connection to test through 
         * <code>ConnectionPoolDatasource</code> and 
         * <code>PooledConnection</code>.
         * Currently, the connection obtained will be either a 
         * <code>LogicalConnection<code> or a <code>BrokeredConnection</code>,
         * depending on whether we run in embedded or client mode.
         */
        public void setUp()
            throws SQLException {
            //The ConnectionPoolDataSource object
            //used to get a PooledConnection object
            ConnectionPoolDataSource cpDataSource = getConnectionPoolDataSource();
            PooledConnection pConn = cpDataSource.getPooledConnection();
            //doing a getConnection() returns a Connection object
            //that internally contains a BrokeredConnection40 object
            //this is then used to check the wrapper object
            con = pConn.getConnection();
        }
        
    } // End class PooledConnectionTest

    /**
     * Tests for the real connection in <code>XAConnection</code>.
     *
     * This class subclasses <code>ConnectionTest</code>, and runs the test-
     * methods implemented there. By doing this, we don't have to duplicate
     * the test code. We only run the tests with a different type of 
     * connection.
     */
    public static class XAConnectionTest
        extends ConnectionTest {

        /**
         * Create a test with the given name.
         * 
         * @param name name of the test.
         */
        public XAConnectionTest(String name) {
            super(name);
        }

        /**
         * Return name of the test, with "_XA" appended.
         * Must override this method to be able to separate which connection
         * the failure happened with, since the test methods are all in the
         * superclass.
         *
         * @return the name of the test method in <code>ConnectionTest</code>,
         *      appended with the string "_XA".
         */
        public String getName() {
            return super.getName() + "_XA";
        }

        /**
         * Obtain a connection to test through <code>XADataSource</code> and 
         * <code>XAConnection</code>.
         * Currently, the connection obtained will be either a 
         * <code>LogicalConnection<code> or a <code>BrokeredConnection</code>,
         * depending on whether we run in embedded or client mode.
         */
        public void setUp()
            throws SQLException {
            // Use a XADataSource to obtain a XAConnection object, and
            // finally a "real" connection.
            con = getXADataSource().getXAConnection().getConnection();
        }
        
    } // End class XAConnectionTest
    
} // End class BaseJDBCTestCase
