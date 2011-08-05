/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.hibernate.search.remote.codex.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.document.Fieldable;

import org.hibernate.search.SearchException;
import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.remote.codex.spi.Serializer;
import org.hibernate.search.remote.operations.impl.Add;
import org.hibernate.search.remote.operations.impl.Delete;
import org.hibernate.search.remote.operations.impl.LuceneFieldContext;
import org.hibernate.search.remote.operations.impl.LuceneNumericFieldContext;
import org.hibernate.search.remote.operations.impl.Message;
import org.hibernate.search.remote.operations.impl.Operation;
import org.hibernate.search.remote.operations.impl.OptimizeAll;
import org.hibernate.search.remote.operations.impl.PurgeAll;
import org.hibernate.search.remote.operations.impl.SerializableBinaryField;
import org.hibernate.search.remote.operations.impl.SerializableCustomFieldable;
import org.hibernate.search.remote.operations.impl.SerializableDocument;
import org.hibernate.search.remote.operations.impl.SerializableDoubleField;
import org.hibernate.search.remote.operations.impl.SerializableFieldable;
import org.hibernate.search.remote.operations.impl.SerializableFloatField;
import org.hibernate.search.remote.operations.impl.SerializableIntField;
import org.hibernate.search.remote.operations.impl.SerializableLongField;
import org.hibernate.search.remote.operations.impl.SerializableReaderField;
import org.hibernate.search.remote.operations.impl.SerializableStringField;
import org.hibernate.search.remote.operations.impl.SerializableTokenStreamField;
import org.hibernate.search.remote.operations.impl.Update;

/**
 * @author Emmanuel Bernard <emmanuel@hibernate.org>
 */
public class ModelSerializer implements Serializer {
	private Set<Operation> ops;
	private Set<SerializableFieldable> serialFields;
	private SerializableDocument currentDocument;

	@Override
	public void luceneWorks(List<LuceneWork> works) {
		ops = new HashSet<Operation>( works.size() );
	}

	@Override
	public void addOptimizeAll() {
		ops.add( new OptimizeAll() );
	}

	@Override
	public void addPurgeAll(String entityClassName) {
		ops.add( new PurgeAll( entityClassName ) );
	}

	@Override
	public void addDelete(String entityClassName, Serializable id) {
		ops.add( new Delete( entityClassName, id ) );
	}

	@Override
	public void addAdd(String entityClassName, Serializable id, Map<String, String> fieldToAnalyzerMap) {
		ops.add( new Add( entityClassName, id, currentDocument, fieldToAnalyzerMap ) );
		clearDocument();
	}

	@Override
	public void addUpdate(String entityClassName, Serializable id, Map<String, String> fieldToAnalyzerMap) {
		ops.add( new Update( entityClassName, id, currentDocument, fieldToAnalyzerMap ) );
		clearDocument();
	}

	@Override
	public byte[] serialize() {
		Message message = new Message( 1, ops );
		//no need to close ByteArrayOutputStream
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ObjectOutputStream stream = new ObjectOutputStream(out);
			stream.writeObject( message );
			stream.close();
		}
		catch ( IOException e ) {
			throw new SearchException( "Unable to serialize work", e );
		}
		return out.toByteArray();
	}

	@Override
	public void fields(List<Fieldable> fields) {
		serialFields = new HashSet<SerializableFieldable>( fields.size() );
	}

	@Override
	public void addIntNumericField(int value, LuceneNumericFieldContext context) {
		serialFields.add( new SerializableIntField(value, context) );
	}

	@Override
	public void addLongNumericField(long value, LuceneNumericFieldContext context) {
		serialFields.add( new SerializableLongField(value, context) );
	}

	@Override
	public void addFloatNumericField(float value, LuceneNumericFieldContext context) {
		serialFields.add( new SerializableFloatField(value, context) );
	}

	@Override
	public void addDoubleNumericField(double value, LuceneNumericFieldContext context) {
		serialFields.add( new SerializableDoubleField(value, context) );
	}

	@Override
	public void addFieldWithBinaryData(LuceneFieldContext luceneFieldContext) {
		serialFields.add( new SerializableBinaryField( luceneFieldContext ) );
	}

	@Override
	public void addFieldWithStringData(LuceneFieldContext luceneFieldContext) {
		serialFields.add( new SerializableStringField( luceneFieldContext ) );
	}

	@Override
	public void addFieldWithTokenStreamData(LuceneFieldContext luceneFieldContext) {
		serialFields.add( new SerializableTokenStreamField( luceneFieldContext ) );
	}

	@Override
	public void addFieldWithSerializableReaderData(LuceneFieldContext luceneFieldContext) {
		serialFields.add( new SerializableReaderField( luceneFieldContext ) );
	}

	@Override
	public void addFieldWithSerializableFieldable(Serializable fieldable) {
		serialFields.add( new SerializableCustomFieldable( fieldable ) );
	}

	@Override
	public void addDocument(float boost) {
		currentDocument = new SerializableDocument( serialFields, boost );
	}

	private void clearDocument() {
		currentDocument = null;
		serialFields = null;
	}
}
