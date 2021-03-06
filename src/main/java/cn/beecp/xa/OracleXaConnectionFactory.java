/*
 * Copyright Chris2018998
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
package cn.beecp.xa;

import oracle.jdbc.xa.client.OracleXAConnection;

import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Oracle XaConnection Factory
 *
 * @author Chris Liao
 * @version 1.0
 */
public class OracleXaConnectionFactory implements XaConnectionFactory {

    /**
     * Create XAConnection instance
     *
     * @param rawCon raw connection from pool
     * @return XAConnection
     * @throws SQLException if failed then throw SQLException
     */
    public XAConnection create(Connection rawCon) throws SQLException {
        try {
            return new OracleXAConnection(rawCon);
        } catch (XAException e) {
            throw new SQLException(e);
        }
    }
}
