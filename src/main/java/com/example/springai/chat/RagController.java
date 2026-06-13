package com.example.springai.chat;

import com.example.springai.rag.DocumentIngestionService;
import com.example.springai.rag.RagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final DocumentIngestionService documentIngestionService;
    private final RagService ragService;

    public RagController(DocumentIngestionService documentIngestionService, RagService ragService) {
        this.documentIngestionService = documentIngestionService;
        this.ragService = ragService;
    }

    @PostMapping("/documents")
    public DocumentIngestionService.IngestionResult addDocument(@RequestBody String text) {
        return documentIngestionService.ingestText(text, "manual");
    }

    @PostMapping("/ingest/text")
    public DocumentIngestionService.IngestionResult ingestText(
            @RequestBody String text,
            @RequestParam(required = false) String source) {
        return documentIngestionService.ingestText(text, source);
    }

    @PostMapping("/ingest/file")
    public DocumentIngestionService.IngestionResult ingestFile(@RequestParam("file") MultipartFile file)
            throws IOException {
        return documentIngestionService.ingestFile(file);
    }

    @GetMapping("/ask")
    public RagService.RagAnswer ask(@RequestParam String question) {
        return ragService.ask(question);
    }
}
