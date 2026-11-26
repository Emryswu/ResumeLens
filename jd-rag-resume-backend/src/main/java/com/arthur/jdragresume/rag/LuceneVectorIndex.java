package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.ResumeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LuceneVectorIndex implements AutoCloseable {
    private static final String VECTOR = "embedding";
    private static final String RESUME_ID = "resumeId";
    private static final String SOURCE_HASH = "sourceHash";
    private static final String CHUNK_INDEX = "chunkIndex";

    private final ObjectMapper objectMapper;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final Object writeLock = new Object();

    @Autowired
    public LuceneVectorIndex(
            ObjectMapper objectMapper,
            @Value("${app.rag.lucene-index-dir:data/lucene/resume-vectors}") String indexDir
    ) throws IOException {
        this(objectMapper, Path.of(indexDir).toAbsolutePath().normalize(), null);
    }

    LuceneVectorIndex(ObjectMapper objectMapper, Path path, SearcherFactory searcherFactory) throws IOException {
        this.objectMapper = objectMapper;
        Files.createDirectories(path);
        this.directory = FSDirectory.open(path);
        IndexWriter openedWriter = null;
        try {
            openedWriter = new IndexWriter(directory, new IndexWriterConfig());
            this.searcherManager = new SearcherManager(openedWriter, searcherFactory);
            this.writer = openedWriter;
        } catch (IOException | RuntimeException | Error failure) {
            try (directory; Closeable rollback = openedWriter == null ? null : openedWriter::rollback) {
                throw failure;
            }
        }
    }

    public boolean isCurrent(Long resumeId, String sourceHash, int expectedChunks) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            BooleanQuery query = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(RESUME_ID, resumeId.toString())), BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(SOURCE_HASH, sourceHash)), BooleanClause.Occur.MUST)
                    .build();
            return searcher.count(query) == expectedChunks;
        } finally {
            searcherManager.release(searcher);
        }
    }

    public void replace(Long resumeId, List<ResumeChunk> chunks) throws IOException {
        synchronized (writeLock) {
            writer.deleteDocuments(new Term(RESUME_ID, resumeId.toString()));
            for (ResumeChunk chunk : chunks) {
                float[] vector = objectMapper.readValue(chunk.getEmbedding(), float[].class);
                Document document = new Document();
                document.add(new StringField(RESUME_ID, resumeId.toString(), Field.Store.NO));
                document.add(new StringField(SOURCE_HASH, chunk.getSourceHash(), Field.Store.NO));
                document.add(new StoredField(CHUNK_INDEX, chunk.getChunkIndex()));
                document.add(new KnnFloatVectorField(VECTOR, vector, VectorSimilarityFunction.COSINE));
                writer.addDocument(document);
            }
            writer.commit();
            searcherManager.maybeRefreshBlocking();
        }
    }

    public void delete(Long resumeId) throws IOException {
        synchronized (writeLock) {
            writer.deleteDocuments(new Term(RESUME_ID, resumeId.toString()));
            writer.commit();
            searcherManager.maybeRefreshBlocking();
        }
    }

    public List<VectorHit> search(Long resumeId, List<float[]> queries, int limit) throws IOException {
        Map<Integer, Float> bestScores = new LinkedHashMap<>();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TermQuery filter = new TermQuery(new Term(RESUME_ID, resumeId.toString()));
            for (float[] query : queries) {
                ScoreDoc[] hits = searcher.search(new KnnFloatVectorQuery(VECTOR, query, limit, filter), limit).scoreDocs;
                for (ScoreDoc hit : hits) {
                    int chunkIndex = searcher.storedFields().document(hit.doc).getField(CHUNK_INDEX).numericValue().intValue();
                    bestScores.merge(chunkIndex, hit.score, Math::max);
                }
            }
        } finally {
            searcherManager.release(searcher);
        }
        List<VectorHit> result = new ArrayList<>();
        bestScores.forEach((index, score) -> result.add(new VectorHit(index, score)));
        result.sort((left, right) -> Float.compare(right.score(), left.score()));
        return result.stream().limit(limit).toList();
    }

    @PreDestroy
    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            try (directory; writer; searcherManager) {
                // Close in reverse order: searcherManager -> writer -> directory.
            }
        }
    }

    public record VectorHit(int chunkIndex, float score) {}
}
