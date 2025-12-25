package com.example.webhook_example.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/webhook")
public class WhatsappWebhookController {

    private static final Logger log =
            LoggerFactory.getLogger(WhatsappWebhookController.class);

    private final WebClient webClient;

    public WhatsappWebhookController(WebClient webClient) {
        this.webClient = webClient;
    }

    @Value("${whatsapp.verify-token}")
    private String VERIFY_TOKEN;

    @Value("${whatsapp.access-token}")
    private String ACCESS_TOKEN;

    @Value("${whatsapp.phone-number-id}")
    private String PHONE_NUMBER_ID;

    // ================== VERIFICATION ==================
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {

        if ("subscribe".equals(mode) && VERIFY_TOKEN.equals(token)) {
            log.info("Webhook verified successfully");
            return ResponseEntity.ok(challenge);
        }

        log.warn("Webhook verification failed");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    // ================== RECEIVE EVENTS ==================
    @PostMapping
    public ResponseEntity<String> receiveMessage(@RequestBody Map<String, Object> payload) {

        try {
            log.debug("Incoming payload: {}", payload);

            List<Map<String, Object>> entries =
                    (List<Map<String, Object>>) payload.get("entry");
            if (entries == null || entries.isEmpty()) {
                return ok();
            }

            Map<String, Object> changes =
                    ((List<Map<String, Object>>) entries.get(0).get("changes")).get(0);
            Map<String, Object> value =
                    (Map<String, Object>) changes.get("value");

            // Ignore statuses, delivery reports, etc.
            if (!value.containsKey("messages")) {
                return ok();
            }

            Map<String, Object> message =
                    ((List<Map<String, Object>>) value.get("messages")).get(0);

            String from = (String) message.get("from");
            String type = (String) message.get("type");

            // ========== TEXT MESSAGE ==========
            if ("text".equals(type)) {
                handleTextMessage(message, from);
            }

            // ========== BUTTON REPLY ==========
            else if ("interactive".equals(type)) {
                handleButtonReply(message, from);
            }

        } catch (Exception e) {
            log.error("Error processing webhook", e);
        }

        return ok();
    }

    // ================== TEXT HANDLER ==================
    private void handleTextMessage(Map<String, Object> message, String from) {

        Map<String, Object> textObj =
                (Map<String, Object>) message.get("text");
        if (textObj == null) return;

        String body = (String) textObj.get("body");

        // Expected: name | date | clinic
        String[] parts = body.split("\\|");
        if (parts.length != 3) {
            log.warn("Invalid message format: {}", body);
            return;
        }

        String name = parts[0].trim();
        String date = parts[1].trim();
        String clinic = parts[2].trim();

        log.info("Appointment request -> name={}, date={}, clinic={}",
                name, date, clinic);

        sendConfirmationMessage(from, name, clinic, date);
    }

    // ================== BUTTON HANDLER ==================
    private void handleButtonReply(Map<String, Object> message, String from) {

        Map<String, Object> interactive =
                (Map<String, Object>) message.get("interactive");
        Map<String, Object> buttonReply =
                (Map<String, Object>) interactive.get("button_reply");

        String replyId = (String) buttonReply.get("id");

        if ("confirm".equals(replyId)) {
            log.info("Appointment CONFIRMED by {}", from);
        } else if ("cancel".equals(replyId)) {
            log.info("Appointment CANCELED by {}", from);
        }
    }

    // ================== SEND CONFIRM MESSAGE ==================
    private void sendConfirmationMessage(
            String to, String name, String clinic, String date) {

        String url =
                "https://graph.facebook.com/v17.0/" +
                        PHONE_NUMBER_ID + "/messages";

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of(
                                "text",
                                "Hello " + name +
                                        ", please confirm your appointment at "
                                        + clinic + " on " + date
                        ),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply",
                                                "reply", Map.of(
                                                        "id", "confirm",
                                                        "title", "Confirm"
                                                )),
                                        Map.of("type", "reply",
                                                "reply", Map.of(
                                                        "id", "cancel",
                                                        "title", "Cancel"
                                                ))
                                )
                        )
                )
        );

        webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + ACCESS_TOKEN)
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(res -> log.info("WhatsApp API response: {}", res))
                .doOnError(err -> log.error("Failed to send message", err))
                .subscribe();
    }

    private ResponseEntity<String> ok() {
        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
