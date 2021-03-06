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
package cn.beecp.test.base;

import java.sql.Connection;
import java.sql.SQLException;

import cn.beecp.BeeDataSource;
import cn.beecp.BeeDataSourceConfig;
import cn.beecp.pool.FastConnectionPool;
import cn.beecp.test.Config;
import cn.beecp.test.TestCase;
import cn.beecp.test.TestUtil;

public class ConnectionHoldTimeoutTest extends TestCase {
	private BeeDataSource ds;

	public void setUp() throws Throwable {
		BeeDataSourceConfig config = new BeeDataSourceConfig();
		config.setJdbcUrl(Config.JDBC_URL);
		config.setDriverClassName(Config.JDBC_DRIVER);
		config.setUsername(Config.JDBC_USER);
		config.setPassword(Config.JDBC_PASSWORD);
		config.setInitialSize(0);
		config.setConnectionTestSQL("SELECT 1 from dual");

		config.setHoldTimeout(1000);// hold and not using connection;
		config.setIdleCheckTimeInterval(1000L);// two seconds interval
		config.setIdleCheckTimeInitDelay(0);
		config.setWaitTimeToClearPool(0);
		ds = new BeeDataSource(config);
	}

	public void tearDown() throws Throwable {
		ds.close();
	}

	public void test() throws InterruptedException, Exception {
		Connection con = null;
		try {
			FastConnectionPool pool = (FastConnectionPool) TestUtil.getPool(ds);
			con = ds.getConnection();

			if (pool.getConnTotalSize() != 1)
				TestUtil.assertError("Total connections not as expected 1");
			if (pool.getConnUsingSize() != 1)
				TestUtil.assertError("Using connections not as expected 1");

			Thread.sleep(4000);
			if (pool.getConnUsingSize() != 0)
				TestUtil.assertError("Using connections not as expected 0 after hold timeout");

			try {
				con.getCatalog();
				TestUtil.assertError("must throw closed exception");
			} catch (SQLException e) {
				System.out.println(e);
			}

			Thread.sleep(4000);
		} finally {
			if(con!=null)
				TestUtil.oclose(con);
		}
	}
}
