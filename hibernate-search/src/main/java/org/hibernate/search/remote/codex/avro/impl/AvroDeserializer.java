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
package org.hibernate.search.remote.codex.avro.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.lucene.util.AttributeImpl;

import org.hibernate.search.SearchException;
import org.hibernate.search.remote.codex.impl.SerializationHelper;
import org.hibernate.search.remote.codex.spi.Deserializer;
import org.hibernate.search.remote.codex.spi.LuceneHydrator;
import org.hibernate.search.remote.operations.impl.Index;
import org.hibernate.search.remote.operations.impl.Store;
import org.hibernate.search.remote.operations.impl.TermVector;
import org.hibernate.search.util.logging.impl.Log;
import org.hibernate.search.util.logging.impl.LoggerFactory;

/**
 * @author Emmanuel Bernard <emmanuel@hibernate.org>
 */
public class AvroDeserializer implements Deserializer {

	private final Map<String, Schema> schemas;
	private static final Log log = LoggerFactory.make();

	public AvroDeserializer(Map<String, Schema> schemas) {
		this.schemas = schemas;
	}

	@Override
	public void deserialize(byte[] data, LuceneHydrator hydrator) {
		ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
		int majorVersion = inputStream.read();
		int minorVersion = inputStream.read();
		if ( AvroSerializerProvider.getMajorVersion() != majorVersion ) {
			throw new SearchException(
					"Unable to parse message from protocol version "
							+ majorVersion + "." + minorVersion
							+ ". Current protocol version: "
							+ AvroSerializerProvider.getMajorVersion()
							+ "." + AvroSerializerProvider.getMinorVersion() );
		}
		if ( AvroSerializerProvider.getMinorVersion() < minorVersion ) {
			//TODO what to do about it? Log each time? Once?
			if ( log.isTraceEnabled() ) {
				log.tracef( "Parsing message from a future protocol version. Some feature might not be propagated. Message version: "
								+ majorVersion + "." + minorVersion
								+ ". Current protocol version: "
								+ AvroSerializerProvider.getMajorVersion()
								+ "." + AvroSerializerProvider.getMinorVersion()
				);
			}
		}

		Decoder decoder = DecoderFactory.get().binaryDecoder( inputStream, null );
		GenericDatumReader<GenericRecord> reader = new GenericDatumReader<GenericRecord>( schemas.get("Message") );
		GenericRecord result;
		try {
			result = reader.read(null, decoder);
		}
		catch ( IOException e ) {
			throw new SearchException( "Unable to deserialize Avro stream", e );
		}

		if ( asInt( result, "version" ) != 1 ) {
			throw new SearchException( "Serialization protocol not supported. Protocol version: " + result.get(
					"version"
			) );
		}
		List<GenericRecord> operations = asListOfGenericRecords( result, "operations" );
		for ( GenericRecord operation : operations ) {
			String schema = operation.getSchema().getName();
			if ( "OptimizeAll".equals( schema ) ) {
				hydrator.addOptimizeAll();
			}
			else if ( "PurgeAll".equals( schema ) ) {
				hydrator.addPurgeAllLuceneWork( asString( operation, "class" ) );
			}
			else if ( "Delete".equals( schema ) ) {
				hydrator.addDeleteLuceneWork(
						asString( operation, "class" ),
						asByteArray( operation, "id" )
				);
			}
			else if ( "Add".equals( schema ) ) {
				buildLuceneDocument( asGenericRecord( operation, "document" ), hydrator );
				hydrator.addAddLuceneWork(
						asString( operation, "class" ),
						asByteArray( operation, "id" ),
						( Map<String, String> ) operation.get( "fieldToAnalyzerMap" )
				);
			}
			else if ( "Update".equals( schema ) ) {
				buildLuceneDocument( asGenericRecord( operation, "document" ), hydrator );
				hydrator.addAddLuceneWork(
						asString( operation, "class" ),
						asByteArray( operation, "id" ),
						(Map<String,String>) operation.get( "fieldToAnalyzerMap" )
				);
			}
			else {
				throw new SearchException( "Unexpected operation type: " + schema );
			}
		}
	}

	private void buildLuceneDocument(GenericRecord document, LuceneHydrator hydrator) {
		hydrator.defineDocument( asFloat( document, "boost" ) );
		List<GenericRecord> fieldables = asListOfGenericRecords( document, "fieldables" );
		for ( GenericRecord field : fieldables ) {
			String schema = field.getSchema().getName();
			if ( "CustomFieldable".equals( schema ) ) {
				hydrator.addFieldable( asByteArray( field, "instance" ) );
			}
			else if ( "NumericIntField".equals( schema ) ) {
				hydrator.addIntNumericField(
							asInt( field, "value" ),
							asString( field, "name" ),
							asInt( field, "precisionStep" ),
							asStore( field ),
							asBoolean(field, "indexed"),
							asBoolean( field, "omitNorms" ),
							asBoolean( field, "omitTermFreqAndPositions" )
				);
			}
			else if ( "NumericFloatField".equals( schema ) ) {
				hydrator.addFloatNumericField(
							asFloat( field, "value" ),
							asString( field, "name" ),
							asInt( field, "precisionStep" ),
							asStore( field ),
							asBoolean(field, "indexed"),
							asBoolean( field, "omitNorms" ),
							asBoolean( field, "omitTermFreqAndPositions" )
				);
			}
			else if ( "NumericLongField".equals( schema ) ) {
				hydrator.addLongNumericField(
							asLong( field, "value" ),
							asString( field, "name" ),
							asInt( field, "precisionStep" ),
							asStore( field ),
							asBoolean(field, "indexed"),
							asBoolean( field, "omitNorms" ),
							asBoolean( field, "omitTermFreqAndPositions" )
				);
			}
			else if ( "NumericDoubleField".equals( schema ) ) {
				hydrator.addDoubleNumericField(
							asDouble( field, "value" ),
							asString( field, "name" ),
							asInt( field, "precisionStep" ),
							asStore( field ),
							asBoolean(field, "indexed"),
							asBoolean( field, "omitNorms" ),
							asBoolean( field, "omitTermFreqAndPositions" )
				);
			}
			else if ( "BinaryField".equals( schema ) ) {
				hydrator.addFieldWithBinaryData(
							asString( field, "name" ),
							asByteArray( field, "value" ),
							asInt( field, "offset" ),
							asInt( field, "length" ),
							asFloat( field, "boost" ),
							asBoolean( field, "omitNorms" ),
							asBoolean( field, "omitTermFreqAndPositions" )
				);
			}
			else if ( "StringField".equals( schema ) ) {
				hydrator.addFieldWithStringData(
						asString( field, "name" ),
						asString( field, "value" ),
						asStore( field ),
						asIndex( field ),
						asTermVector( field ),
						asFloat( field, "boost" ),
						asBoolean( field, "omitNorms" ),
						asBoolean( field, "omitTermFreqAndPositions" )
				);
			}
			else if ( "TokenStreamField".equals( schema ) ) {
				hydrator.addFieldWithTokenStreamData(
						asString( field, "name" ),
						//FIXME remove serialization
						( List<List<AttributeImpl>> ) SerializationHelper.toSerializable(
								asByteArray( field, "value" ), Thread.currentThread().getContextClassLoader() ),
						asTermVector( field ),
						asFloat( field, "boost" ),
						asBoolean( field, "omitNorms" ),
						asBoolean( field, "omitTermFreqAndPositions" )
				);
			}
			else if ( "ReaderField".equals( schema ) ) {
				hydrator.addFieldWithSerializableReaderData(
						asString( field, "name" ),
						asByteArray( field, "value" ),
						asTermVector( field ),
						asFloat( field, "boost" ),
						asBoolean( field, "omitNorms" ),
						asBoolean( field, "omitTermFreqAndPositions" )
				);
			}
			else {
				throw new SearchException( "Unknown Field type: " + schema );
			}
		}










/*

				else if ( field instanceof SerializableTokenStreamField ) {
					SerializableTokenStreamField reallySafeField = ( SerializableTokenStreamField ) field;
					hydrator.addFieldWithTokenStreamData(
							reallySafeField.getName(),
							reallySafeField.getValue().getStream(),
							reallySafeField.getTermVector(),
							safeField.getBoost(),
							safeField.isOmitNorms(),
							safeField.isOmitTermFreqAndPositions()
					);
				}
				else if ( field instanceof SerializableReaderField ) {
					SerializableReaderField reallySafeField = ( SerializableReaderField ) field;
					hydrator.addFieldWithSerializableReaderData(
							reallySafeField.getName(),
							reallySafeField.getValue(),
							reallySafeField.getTermVector(),
							safeField.getBoost(),
							safeField.isOmitNorms(),
							safeField.isOmitTermFreqAndPositions()
					);
				}
				else {
					throw new SearchException( "Unknown SerializableField: " + field.getClass() );
				}
			}
			else {
				throw new SearchException( "Unknown SerializableFieldable: " + field.getClass() );
			}
		}       */
	}

	private GenericRecord asGenericRecord(GenericRecord operation, String field) {
		return (GenericRecord) operation.get(field);
	}

	private List<GenericRecord> asListOfGenericRecords(GenericRecord result, String field) {
		return (List<GenericRecord>) result.get(field);
	}

	private float asFloat(GenericRecord record, String field) {
		return ( (Float) record.get(field) ).floatValue();
	}

	private int asInt(GenericRecord record, String field) {
		return ( (Integer) record.get(field) ).intValue();
	}

	private long asLong(GenericRecord record, String field) {
		return ( (Long) record.get(field) ).longValue();
	}

	private double asDouble(GenericRecord record, String field) {
		return ( (Double) record.get(field) ).doubleValue();
	}

	private String asString(GenericRecord record, String field) {
		return record.get(field).toString();
	}

	private boolean asBoolean(GenericRecord record, String field) {
		return ( (Boolean) record.get(field) ).booleanValue();
	}

	private Store asStore(GenericRecord field) {
		String string = field.get("store").toString();
		return Store.valueOf( string );
	}

	private Index asIndex(GenericRecord field) {
		String string = field.get("index").toString();
		return Index.valueOf( string );
	}

	private TermVector asTermVector(GenericRecord field) {
		String string = field.get("termVector").toString();
		return TermVector.valueOf( string );
	}

	private byte[] asByteArray(GenericRecord operation, String field) {
		ByteBuffer buffer = (ByteBuffer) operation.get(field);
		byte[] copy = new byte[buffer.remaining()];
		buffer.get( copy );
		return copy;
	}
}
