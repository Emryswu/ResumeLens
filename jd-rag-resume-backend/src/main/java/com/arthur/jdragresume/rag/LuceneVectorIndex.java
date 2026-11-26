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
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LuceneVectorIndex implements ResumeVectorStore, AutoCloseable {
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

    @Override
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

    @Override
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

    @Override
    public void delete(Long resumeId) throws IOException {
        synchronized (writeLock) {
            writer.deleteDocuments(new Term(RESUME_ID, resumeId.toString()));
            writer.commit();
            searcherManager.maybeRefreshBlocking();
        }
    }

    @Override
    public Set<Long> indexedResumeIds() throws IOException {
        Set<Long> result = new HashSet<>();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
                Terms terms = leaf.reader().terms(RESUME_ID);
                if (terms == null) {
                    continue;
                }
                Bits liveDocs = leaf.reader().getLiveDocs();
                TermsEnum iterator = terms.iterator();
                BytesRef bytes;
                while ((bytes = iterator.next()) != null) {
                    if (!hasLiveDocument(iterator, liveDocs)) {
                        continue;
                    }
                    try {
                        result.add(Long.parseLong(bytes.utf8ToString()));
                    } catch (NumberFormatException ignored) {
                        // Malformed legacy terms are cleaned by retainOnly if ever encountered.
                    }
                }
            }
        } finally {
            searcherManager.release(searcher);
        }
        return Set.copyOf(result);
    }

    @Override
    public int deleteAll(Set<Long> resumeIds) throws IOException {
        if (resumeIds == null || resumeIds.isEmpty()) {
            return 0;
        }
        Term[] terms = resumeIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(id -> new Term(RESUME_ID, id.toString()))
                .toArray(Term[]::new);
        if (terms.length == 0) {
            return 0;
        }
        synchronized (writeLock) {
            writer.deleteDocuments(terms);
            writer.commit();
            searcherManager.maybeRefreshBlocking();
        }
        return terms.length;
    }

    @Override
    public int retainOnly(Set<Long> activeResumeIds) throws IOException {
        Set<Long> active = activeResumeIds == null ? Set.of() : activeResumeIds;
        synchronized (writeLock) {
            List<Term> orphanTerms = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
                    Terms terms = leaf.reader().terms(RESUME_ID);
                    if (terms == null) {
                        continue;
                    }
                    Bits liveDocs = leaf.reader().getLiveDocs();
                    TermsEnum iterator = terms.iterator();
                    BytesRef bytes;
                    while ((bytes = iterator.next()) != null) {
                        if (!hasLiveDocument(iterator, liveDocs)) {
                            continue;
                        }
                        String idText = bytes.utf8ToString();
                        if (!seenIds.add(idText)) {
                            continue;
                        }
                        try {
                            long resumeId = Long.parseLong(idText);
                            if (!active.contains(resumeId)) {
                                orphanTerms.add(new Term(RESUME_ID, idText));
                            }
                        } catch (NumberFormatException ignored) {
                            orphanTerms.add(new Term(RESUME_ID, idText));
                        }
                    }
                }
            } finally {
                searcherManager.release(searcher);
            }
            if (orphanTerms.isEmpty()) {
                return 0;
            }
            writer.deleteDocuments(orphanTerms.toArray(Term[]::new));
            writer.commit();
            searcherManager.maybeRefreshBlocking();
            return orphanTerms.size();
        }
    }

    private static boolean hasLiveDocument(TermsEnum iterator, Bits liveDocs) throws IOException {
        if (liveDocs == null) {
            return iterator.docFreq() > 0;
        }
        PostingsEnum postings = iterator.postings(null, PostingsEnum.NONE);
        if (postings == null) {
            return false;
        }
        int docId;
        while ((docId = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
            if (liveDocs.get(docId)) {
                return true;
            }
        }
        return false;
    }

    @Override
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
