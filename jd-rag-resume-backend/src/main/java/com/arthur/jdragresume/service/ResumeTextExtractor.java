package com.arthur.jdragresume.service;

import com.arthur.jdragresume.exception.BusinessException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.AutoDetectParserConfig;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Service
public class ResumeTextExtractor {
    static final int MAX_RAW_TEXT_CHARS = 200_000;
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md");

    private static final EmbeddedDocumentExtractor SKIP_EMBEDDED = new EmbeddedDocumentExtractor() {
        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return false;
        }

        @Override
        public void parseEmbedded(
                InputStream stream,
                ContentHandler handler,
                Metadata metadata,
                boolean outputHtml
        ) {
            // Resume matching only needs the outer document text.
        }
    };

    private static final Logger log = LoggerFactory.getLogger(ResumeTextExtractor.class);

    private final AutoDetectParser parser;
    private final ResumeTextQualityValidator qualityValidator;

    public ResumeTextExtractor(ResumeTextQualityValidator qualityValidator) {
        this.qualityValidator = qualityValidator;
        AutoDetectParser autoDetectParser = new AutoDetectParser();
        AutoDetectParserConfig config = new AutoDetectParserConfig();
        // maximumDepth limits XHTML element nesting, not embedded documents (SKIP_EMBEDDED covers those).
        // Bold or italic runs in an ordinary DOCX already nest past 4, so keep Tika's default.
        config.setMaximumPackageEntryDepth(2);
        autoDetectParser.setAutoDetectParserConfig(config);
        this.parser = autoDetectParser;
    }

    public String extract(MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        try {
            if (PLAIN_TEXT_EXTENSIONS.contains(extension)) {
                return qualityValidator.validate(readPlainText(file));
            }
            return qualityValidator.validate(parseWithTika(file));
        } catch (BusinessException ex) {
            throw ex;
        } catch (WriteLimitReachedException ex) {
            throw tooLong();
        } catch (IOException | SAXException | TikaException ex) {
            if (WriteLimitReachedException.isWriteLimitReached(ex)) {
                throw tooLong();
            }
            log.warn("Failed to parse uploaded resume with extension {}", extension, ex);
            throw new BusinessException("RESUME_PARSE_FAILED", "无法读取简历文件内容，请确认文件未损坏或加密，也可以直接粘贴简历正文");
        }
    }

    private String readPlainText(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > MAX_RAW_TEXT_CHARS) {
            throw tooLong();
        }
        return text;
    }

    private String parseWithTika(MultipartFile file) throws IOException, SAXException, TikaException {
        try (InputStream inputStream = file.getInputStream()) {
            BodyContentHandler handler = new BodyContentHandler(MAX_RAW_TEXT_CHARS);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            context.set(EmbeddedDocumentExtractor.class, SKIP_EMBEDDED);
            parser.parse(inputStream, handler, metadata, context);
            return handler.toString();
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private static BusinessException tooLong() {
        return new BusinessException(
                "RESUME_TEXT_TOO_LONG",
                "resume text exceeds the maximum of " + MAX_RAW_TEXT_CHARS + " characters"
        );
    }
}
