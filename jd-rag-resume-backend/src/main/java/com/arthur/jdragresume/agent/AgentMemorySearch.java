package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.entity.AgentMemory;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Uses the existing Lucene dependency, with no embeddings or changes to the resume index. */
@Component
public class AgentMemorySearch {
    public List<AgentMemory> search(Long userId, String query, List<AgentMemory> memories, int limit) {
        if (memories.isEmpty()) return List.of();
        // General job-planning questions need explicit goals even when no city/role is named again.
        String expanded = query.matches("(?s).*(职位|岗位|准备|公司|求职|投递|找工作|哪几家).*")
                ? query + " 目标城市 意向城市 目标方向 求职方向 短板 不擅长" : query;
        try (var directory = new ByteBuffersDirectory(); var writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int index = 0; index < memories.size(); index++) {
                AgentMemory memory = memories.get(index);
                Document document = new Document();
                document.add(new StringField("user", memory.getUser().getId().toString(), Field.Store.NO));
                document.add(new StoredField("position", index));
                for (String token : tokens(memory.getContent())) {
                    document.add(new StringField("term", token, Field.Store.NO));
                }
                writer.addDocument(document);
            }
            try (var reader = DirectoryReader.open(writer)) {
                var searcher = new IndexSearcher(reader);
                var filter = new BooleanQuery.Builder()
                        .add(new TermQuery(new Term("user", userId.toString())), BooleanClause.Occur.FILTER)
                        .add(new TermInSetQuery("term", tokens(expanded).stream().map(BytesRef::new).toList()), BooleanClause.Occur.MUST)
                        .build();
                List<AgentMemory> result = new ArrayList<>();
                for (var hit : searcher.search(filter, limit).scoreDocs) {
                    int position = searcher.storedFields().document(hit.doc).getField("position").numericValue().intValue();
                    result.add(memories.get(position));
                }
                return List.copyOf(result);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("agent memory search failed", ex);
        }
    }

    /** Chinese bigrams plus Latin words, without another analyzer/model dependency. */
    private static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) continue;
            result.add(word);
            int[] points = word.codePoints().toArray();
            for (int index = 0; index + 1 < points.length; index++) {
                result.add(new String(points, index, 2));
            }
        }
        return result;
    }
}
