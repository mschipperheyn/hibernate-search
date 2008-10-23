package org.hibernate.search.backend.impl.lucene;

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.slf4j.Logger;

import org.hibernate.annotations.common.AssertionFailure;
import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.backend.Workspace;
import org.hibernate.search.backend.impl.lucene.works.LuceneWorkVisitor;
import org.hibernate.search.util.LoggerFactory;

/**
 * @author Sanne Grinovero
 */
class PerDPQueueProcessor {
	
	private static final Logger log = LoggerFactory.make();
	private final Workspace workspace;
	private final LuceneWorkVisitor worker;
	private final List<LuceneWork> workOnWriter = new ArrayList<LuceneWork>();
	private final List<LuceneWork> workOnReader= new ArrayList<LuceneWork>();
	
	// if any work passed to addWork needs one, set corresponding flag to true:
	private boolean batchmode = false;
	private boolean needsReader = false;
	private boolean needsWriter = false;
	private boolean preferReader = false;
	
	public PerDPQueueProcessor(LuceneWorkVisitor worker) {
		this.worker = worker;
		this.workspace = worker.getWorkspace();
	}

	public void addWork(LuceneWork work) {
		if ( work.isBatch() ) {
			batchmode = true;
			log.debug( "Batch mode enabled" );
		}
		IndexInteractionType type = work.getWorkDelegate( worker ).getIndexInteractionType();
		switch ( type ) {
			case NEEDS_INDEXREADER :
				needsReader = true;
				//fall through:
			case PREFER_INDEXREADER :
				preferReader = true;
				workOnReader.add( work );
				break;
			case NEEDS_INDEXWRITER :
				needsWriter = true;
				//fall through:
			case PREFER_INDEXWRITER :
				workOnWriter.add( work );
				break;
			default :
				throw new AssertionFailure( "Uncovered switch case for type " + type );
		}
	}

	public void performWorks() {
		// skip "resource optimization mode" when in batch to have all tasks use preferred (optimal) mode.
		if ( ! batchmode ) {
			// 	see if we can skip using some resource
			if ( ! needsReader && ! needsWriter ) { // no specific need:
				if ( preferReader ) {
					useReaderOnly();
				}
				else {
					useWriterOnly();
				}
			}
			else if ( needsReader && !needsWriter ) {
				useReaderOnly();
			}
			else if ( !needsReader && needsWriter ) {
				useWriterOnly();
			}
		}
		// apply changes to index:
		log.trace( "Locking Workspace (or waiting to...)" );
		workspace.lock();
		log.trace( "Workspace lock aquired." );
		try {
			performReaderWorks();
			performWriterWorks();
		}
		finally {
			workspace.unlock();
			log.trace( "Unlocking Workspace" );
		}
	}

	/**
	 * Do all workOnWriter on an IndexWriter.
	 */
	private void performWriterWorks() {
		if ( workOnWriter.isEmpty() ) {
			return;
		}
		log.debug( "Opening an IndexWriter for update" );
		IndexWriter indexWriter = workspace.getIndexWriter( batchmode );
		try {
			for (LuceneWork lw : workOnWriter) {
				lw.getWorkDelegate( worker ).performWork( lw, indexWriter );
			}
			//TODO next line is assuming the OptimizerStrategy will need an IndexWriter;
			// would be nicer to have the strategy put an OptimizeWork on the queue,
			// or just return "yes please" (true) to some method?
			//FIXME will not have a chance to trigger when no writer activity is done.
			// this is currently ok, until we enable mod.counts for deletions too.
			workspace.optimizerPhase();
		}
		finally {
			workspace.closeIndexWriter();
		}
		
	}

	/**
	 * Do all workOnReader on an IndexReader.
	 */
	private void performReaderWorks() {
		if ( workOnReader.isEmpty() ) {
			return;
		}
		log.debug( "Opening an IndexReader for update" );
		IndexReader indexReader = workspace.getIndexReader();
		try {
			for (LuceneWork lw : workOnReader) {
				lw.getWorkDelegate( worker ).performWork( lw, indexReader );
			}
		}
		finally {
			workspace.closeIndexReader();
		}
	}

	/**
	 * forces all work to be done using only an IndexReader
	 */
	private void useReaderOnly() {
		log.debug( "Skipping usage of an IndexWriter for updates" );
		workOnReader.addAll( workOnWriter );
		workOnWriter.clear();
	}

	/**
	 * forces all work to be done using only an IndexWriter
	 */
	private void useWriterOnly() {
		log.debug( "Skipping usage of an IndexReader for updates" );
		//position 0 needed to maintain correct ordering of Work: delete operations first.
		workOnWriter.addAll( 0, workOnReader );
		workOnReader.clear();
	}
	
}