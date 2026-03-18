package com.example.springai.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.springai.model.ConversationTextRequest;
import com.example.springai.model.DocumentClassificationResponse;
import com.example.springai.model.DocumentRoutingRequest;
import com.example.springai.model.DocumentRoutingResponse;
import com.example.springai.model.PdfRoutingDebugResponse;
import com.example.springai.model.SentimentResponse;
import com.example.springai.model.SimpleResponse;
import com.example.springai.model.SummarizeResponse;
import com.example.springai.model.TextRequest;
import com.example.springai.service.DocumentClassificationService;
import com.example.springai.service.PdfDocumentReaderService;
import com.example.springai.service.QuestionAnswerService;
import com.example.springai.service.SentimentAnalysisService;
import com.example.springai.service.SummarizationService;

@RestController
@RequestMapping("/ai")
@CrossOrigin(origins = "*")
public class AiController {

    private final SummarizationService summarizationService;
    private final SentimentAnalysisService sentimentAnalysisService;
    private final DocumentClassificationService documentClassificationService;
    private final PdfDocumentReaderService pdfDocumentReaderService;
    private final QuestionAnswerService questionAnswerService;

    public AiController(
            SummarizationService summarizationService,
            SentimentAnalysisService sentimentAnalysisService,
            DocumentClassificationService documentClassificationService,
            PdfDocumentReaderService pdfDocumentReaderService,
            QuestionAnswerService questionAnswerService) {
        this.summarizationService = summarizationService;
        this.sentimentAnalysisService = sentimentAnalysisService;
        this.documentClassificationService = documentClassificationService;
        this.pdfDocumentReaderService = pdfDocumentReaderService;
        this.questionAnswerService = questionAnswerService;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @PostMapping("/answer-question")
    public SimpleResponse answerQuestion(@RequestBody TextRequest request) {
        return questionAnswerService.answerQuestion(request.text());
    }

    @PostMapping("/answer-question-memory")
    public SimpleResponse answerQuestionWithMemory(@RequestBody ConversationTextRequest request) {
        return questionAnswerService.answerQuestion(request.conversationId(), request.text());
    }

    @PostMapping("/summarize")
    public SummarizeResponse summarize(@RequestBody TextRequest request) {
        return summarizationService.summarize(request.text());
    }

    @PostMapping("/analyze-sentiment")
    public SentimentResponse analyzeSentiment(@RequestBody TextRequest request) {
        return sentimentAnalysisService.analyze(request.text());
    }

    @PostMapping("/classify-document")
    public DocumentClassificationResponse classifyDocument(@RequestBody TextRequest request) {
        return documentClassificationService.classify(request.text());
    }

    @PostMapping("/route-document/basic")
    public DocumentRoutingResponse routeDocumentBasic(@RequestBody DocumentRoutingRequest request) {
        return documentClassificationService.routeWithBasicPrompt(request.text());
    }

    @PostMapping("/route-document/memory")
    public DocumentRoutingResponse routeDocumentWithMemory(@RequestBody DocumentRoutingRequest request) {
        return documentClassificationService.routeWithMemory(request.conversationId(), request.text());
    }

    @PostMapping("/route-document/tools")
    public DocumentRoutingResponse routeDocumentWithTools(@RequestBody DocumentRoutingRequest request) {
        return documentClassificationService.routeWithTools(request.text());
    }

    @PostMapping("/route-document/rag")
    public DocumentRoutingResponse routeDocumentWithRag(@RequestBody DocumentRoutingRequest request) {
        return documentClassificationService.routeWithRag(request.text(), request.fundCode());
    }

    @PostMapping("/route-document/hybrid")
    public DocumentRoutingResponse routeDocumentWithHybrid(@RequestBody DocumentRoutingRequest request) {
        return documentClassificationService.routeWithHybrid(request.text());
    }

    @PostMapping(path = "/route-document/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfRoutingDebugResponse routePdfDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "strategy", defaultValue = "rag") String strategy,
            @RequestParam(name = "conversationId", required = false) String conversationId,
            @RequestParam(name = "fundCode", required = false) String fundCode) {
        var extractionResult = pdfDocumentReaderService.extract(file);
        return documentClassificationService.routePdf(strategy, conversationId, fundCode, extractionResult);
    }
}
