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
package com.teradata.jaqy.typeprinter;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLXML;

import com.teradata.jaqy.connection.JaqyResultSet;
import com.teradata.jaqy.utils.StringUtils;

/**
 * @author	Heng Yuan
 */
class XmlTypePrinter implements TypePrinter
{
	private final static TypePrinter s_instance = new XmlTypePrinter ();

	public static TypePrinter getInstance ()
	{
		return s_instance;
	}

	private XmlTypePrinter ()
	{
	}

	@Override
	public void print (PrintWriter pw, JaqyResultSet rs, int columnIndex, int width, boolean leftAlign, boolean pad) throws SQLException
	{
		SQLXML xml = (SQLXML) rs.getObject (columnIndex);
		String value = null;
		if (xml != null)
			value = xml.getString ();
		StringUtils.print (pw, value, width, leftAlign, pad);
	}
}