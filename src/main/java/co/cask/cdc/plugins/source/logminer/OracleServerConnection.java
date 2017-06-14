/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdc.plugins.source.logminer;

import com.google.common.base.Throwables;
import oracle.jdbc.driver.OracleDriver;
import scala.Serializable;
import scala.runtime.AbstractFunction0;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A class which can provide a {@link Connection} using {@link OracleDriver} which is
 * serializable.
 * Note: This class does not do any connection management. Its the responsibility of the client
 * to manage/close the connection.
 */
class OracleServerConnection extends AbstractFunction0<Connection> implements Serializable {
  private String connectionUrl;
  private String userName;
  private String password;

  OracleServerConnection(String connectionUrl, String userName, String password) {
    this.connectionUrl = connectionUrl;
    this.userName = userName;
    this.password = password;
  }

  @Override
  public Connection apply() {
    try {
      Class.forName(OracleDriver.class.getName());
      Properties properties = new Properties();
      properties.setProperty("user", userName);
      properties.setProperty("password", password);
      Connection connection = DriverManager.getConnection(connectionUrl, properties);
      // this might not be here. debugging....
      setUpLogMiner(connection);
      return connection;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }


  private void setUpLogMiner(Connection connection) throws SQLException {
    // Fetch all the redo logs using the following query
    // SELECT distinct member LOGFILENAME FROM V$LOGFILE;
    List<String> redoFiles = new ArrayList<>();
    ResultSet resultSet = connection.createStatement().executeQuery("SELECT distinct member LOGFILENAME FROM V$LOGFILE");
    while (resultSet.next()) {
      redoFiles.add(resultSet.getString(1));
    }

    // Add all the redo files
    // execute DBMS_LOGMNR.ADD_LOGFILE ('/u01/app/oracle/oradata/xe/redo01.log');
    for (String redoFile : redoFiles) {
      String addFileQuery = String.format("BEGIN DBMS_LOGMNR.ADD_LOGFILE('%s'); END;", redoFile);
      System.out.println(addFileQuery);
      CallableStatement callableStatement = connection.prepareCall(addFileQuery);
      callableStatement.execute();
    }

    // Start LogMiner
    // execute DBMS_LOGMNR.START_LOGMNR (options => dbms_logmnr.dict_from_online_catalog);
    CallableStatement callableStatement = connection.prepareCall(
      "BEGIN DBMS_LOGMNR.START_LOGMNR (options => dbms_logmnr.dict_from_online_catalog " +
        "+ DBMS_LOGMNR.COMMITTED_DATA_ONLY + DBMS_LOGMNR.NO_SQL_DELIMITER); END;");
    callableStatement.execute();
  }

//  private void closeLogMiner(Connection connection) throws SQLException {
//    // Stop LogMiner
//    // execute DBMS_LOGMNR.END_LOGMNR
//    CallableStatement callableStatement = connection.prepareCall("execute DBMS_LOGMNR.END_LOGMNR;");
//    callableStatement.execute();
//  }
}