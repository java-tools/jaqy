/*
 * Copyright (c) 2017 Teradata
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.teradata.jaqy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

import com.teradata.jaqy.connection.JaqyConnection;
import com.teradata.jaqy.connection.JaqyParameterMetaData;
import com.teradata.jaqy.connection.JaqyPreparedStatement;
import com.teradata.jaqy.connection.JaqyResultSet;
import com.teradata.jaqy.connection.JaqyStatement;
import com.teradata.jaqy.importer.FieldImporter;
import com.teradata.jaqy.interfaces.Display;
import com.teradata.jaqy.interfaces.JaqyHelper;
import com.teradata.jaqy.interfaces.JaqyHelperFactory;
import com.teradata.jaqy.interfaces.JaqyImporter;
import com.teradata.jaqy.parser.VariableParser;
import com.teradata.jaqy.utils.DriverManagerUtils;

/**
 * @author Heng Yuan
 */
public class Session
{
	private final int m_sessionId;
	private final Globals m_globals;

	private JaqyConnection m_connection;

	private long m_activityCount;
	private final Object m_lock = new Object ();

	Session (Globals globals, int sessionId, Display display)
	{
		m_globals = globals;
		m_sessionId = sessionId;
	}

	private void reset ()
	{
		synchronized (m_lock)
		{
			m_connection = null;
		}
	}

	private void setConnection (String protocol, Connection conn)
	{
		JaqyConnection c = new JaqyConnection (conn);
		JaqyHelperFactory helperFactory = m_globals.getHelperManager ().getHelperFactory (protocol);
		JaqyHelper helper = helperFactory.getHelper (c, m_globals);
		c.setHelper (helper);
		synchronized (m_lock)
		{
			m_connection = c;
		}
	}

	public Globals getGlobals ()
	{
		return m_globals;
	}

	public void open (String url, Properties properties, JaqyInterpreter interpreter, Display display)
	{
		try
		{
			String protocol = DriverManagerUtils.getProtocol (m_globals.getDriverManager (), url);
			if (protocol == null)
			{
				throw new SQLException ("Unknown protocol");
			}

			boolean loaded = m_globals.getDriverManager ().loadDriver (display, interpreter, protocol);
			if (!loaded)
			{
				interpreter.error ("Unable to load driver for " + protocol);
				return;
			}

			// append jdbc: prefix if necessary
			if (!url.startsWith ("jdbc:"))
				url = "jdbc:" + url;
			Connection conn = DriverManager.getConnection (url, properties);
			setConnection (protocol, conn);
		}
		catch (Throwable t)
		{
			interpreter.error (t);
		}
		display.showTitle (interpreter);
	}

	public JaqyConnection getConnection ()
	{
		synchronized (m_lock)
		{
			return m_connection;
		}
	}

	public void close (JaqyInterpreter interpreter, boolean isExit)
	{
		JaqyConnection conn = getConnection ();
		if (conn != null)
		{
			conn.close ();
		}
		reset ();

		if (!isExit)
			interpreter.getDisplay ().showTitle (interpreter);
	}

	public boolean isClosed ()
	{
		JaqyConnection conn = getConnection ();
		if (conn == null)
			return true;
		return conn.isClosed ();
	}

	private JaqyStatement createStatement () throws SQLException
	{
		JaqyConnection conn;
		synchronized (m_lock)
		{
			conn = m_connection;
		}
		return conn.createStatement ();
	}

	private JaqyPreparedStatement prepareStatement (String sql, JaqyInterpreter interpreter) throws SQLException
	{
		JaqyConnection conn;
		synchronized (m_lock)
		{
			conn = m_connection;
		}
		JaqyPreparedStatement stmt = conn.prepareStatement (sql);
		m_globals.getDebugManager ().dumpPreparedStatement (interpreter.getDisplay (), this, stmt);
		return stmt;
	}

	private void handleQueryResult (JaqyStatement stmt, JaqyInterpreter interpreter) throws SQLException
	{
		Display display = interpreter.getDisplay ();
		JaqyResultSet rs = stmt.getResultSet ();
		for (;;)
		{
			if (rs != null)
			{
				m_globals.getDebugManager ().dumpResultSet (display, this, rs);
				display.showSuccess (interpreter);
				long activityCount = interpreter.print (rs);
				setActivityCount (activityCount);
				if (activityCount >= 0)
				{
					display.showActivityCount (interpreter);
				}
				rs.close ();
				rs = null;
			}
			else
			{
				long activityCount = stmt.getUpdateCount ();
				if (activityCount >= 0)
				{
					setActivityCount (activityCount);
					display.showSuccessUpdate (interpreter);
				}
				else
				{
					// we do not actually have a result
					break;
				}
			}
			// move onto the next result set
			boolean moreRS = stmt.getMoreResults ();
			if (moreRS)
			{
				rs = stmt.getResultSet ();
			}
		}
	}

	public JaqyPreparedStatement prepareQuery (String sql, JaqyInterpreter interpreter) throws SQLException
	{
		assert Debug.debug ("prepareQuery: " + sql);
		interpreter.incSqlCount ();
		if (isClosed ())
		{
			interpreter.error ("session closed.");
		}

		// remove trailing ; since some JDBC drivers (notably Apache Derby)
		// does not permit trailing semicolons.
		if (sql.endsWith (";"))
			sql = sql.substring (0, sql.length () - 1);

		return prepareStatement (sql, interpreter);
	}

	public void importQuery (String sql, JaqyInterpreter interpreter) throws Exception
	{
		JaqyImporter<?> importer = interpreter.getImporter ();
		assert Debug.debug ("importQuery: " + importer);
		importer.showSchema (interpreter.getDisplay ());
		FieldImporter fieldImporter = new FieldImporter (importer);
		sql = VariableParser.getString (sql, interpreter.getVariableHandler (), fieldImporter);
		assert Debug.debug ("field sql: " + sql);
		if (fieldImporter.hasFields ())
			importer = fieldImporter;

		JaqyPreparedStatement stmt = prepareQuery (sql, interpreter);
		JaqyParameterMetaData metaData = stmt.getParameterMetaData ();
		int count = metaData.getParameterCount ();
		if (count == 0)
			throw new IOException ("no parameters detected.");
		int[] parameterTypes = new int[count];
		for (int i = 0; i < count; ++i)
			parameterTypes[i] = metaData.getParameterType (i + 1);
		try
		{
			int columns = stmt.getParameterCount ();
			while (importer.next ())
			{
				for (int i = 0; i < columns; ++i)
				{
					switch (parameterTypes[i])
					{
						case Types.TINYINT:
						case Types.SMALLINT:
						case Types.INTEGER:
						case Types.BIGINT:
							stmt.setObject (i + 1, importer.getObject (i, parameterTypes[i]), parameterTypes[i]);
							break;
						default:
							stmt.setObject (i + 1, importer.getObject (i, parameterTypes[i]));
					}
				}
				stmt.addBatch ();
			}

			stmt.executeBatch ();
			handleQueryResult (stmt, interpreter);
		}
		finally
		{
			try
			{
				if (stmt != null)
					stmt.close ();
			}
			catch (Exception ex)
			{
			}
			try
			{
				importer.close ();
			}
			catch (Exception ex)
			{
				assert Debug.debug (ex);
			}
		}
	}

	public void executeQuery (String sql, JaqyInterpreter interpreter) throws SQLException
	{
		assert Debug.debug ("executeQuery: " + sql);
		interpreter.incSqlCount ();
		if (isClosed ())
		{
			interpreter.error ("session closed.");
		}

		// remove trailing ; since some JDBC drivers (notably Apache Derby)
		// does not permit trailing semicolons.
		if (sql.endsWith (";"))
			sql = sql.substring (0, sql.length () - 1);

		JaqyStatement stmt = createStatement ();
		try
		{
			stmt.execute (sql);
			handleQueryResult (stmt, interpreter);
		}
		finally
		{
			try
			{
				if (stmt != null)
					stmt.close ();
			}
			catch (Exception ex)
			{
			}
		}
	}

	/**
	 * @return the sessionId
	 */
	public int getId ()
	{
		return m_sessionId;
	}

	public String getDescription ()
	{
		StringBuffer buffer = new StringBuffer ();
		buffer.append (getId ()).append (" - ");

		JaqyConnection conn = getConnection ();
		try
		{
			if (conn == null || conn.isClosed ())
			{
				buffer.append ("closed.");
			}
			else
			{
				buffer.append (conn.getHelper ().getPath ());
			}
		}
		catch (SQLException ex)
		{
			buffer.append ("closed.");
		}
		return buffer.toString ();
	}

	/**
	 * Sets the activity count.
	 * 
	 * @param activity
	 *            count the activity count
	 */
	public void setActivityCount (long activityCount)
	{
		m_activityCount = activityCount;
	}

	/**
	 * Gets the activity count.
	 * 
	 * @return the activity count
	 */
	public long getActivityCount ()
	{
		return m_activityCount;
	}

	@Override
	public String toString ()
	{
		return Integer.toString (m_sessionId);
	}
}
