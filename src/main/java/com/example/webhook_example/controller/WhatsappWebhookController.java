package com.example.webhook_example.controller;


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

    // Verification GET endpoint (unchanged)
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {

        if ("subscribe".equals(mode) && VERIFY_TOKEN.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    // POST endpoint to receive messages (unchanged parsing)
    @PostMapping
    public ResponseEntity<String> receiveMessage(@RequestBody Map<String, Object> payload) {
        try {
            List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("entry");
            Map<String, Object> change = ((List<Map<String, Object>>) entries.get(0).get("changes")).get(0);
            Map<String, Object> value = (Map<String, Object>) change.get("value");
            Map<String, Object> message = ((List<Map<String, Object>>) value.get("messages")).get(0);

            String from = (String) message.get("from");
            String text = (String) ((Map<String, Object>) message.get("text")).get("body");

            String[] parts = text.split("\\|");
            if (parts.length != 3) return ResponseEntity.ok("Invalid message format");

            String name = parts[0].trim();
            String date = parts[1].trim();
            String clinic = parts[2].trim();

            System.out.println("Name: " + name + ", Date: " + date + ", Clinic: " + clinic);

            // Use WebClient to send reply
            sendConfirmationMessage(from, clinic, date);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    // Send interactive buttons using WebClient
    private void sendConfirmationMessage(String to, String clinic, String date) {
        String url = "https://graph.facebook.com/v17.0/" + PHONE_NUMBER_ID + "/messages";

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", "Please confirm your appointment at " + clinic + " on " + date),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type","reply","reply", Map.of("id","confirm","title","Confirm")),
                                        Map.of("type","reply","reply", Map.of("id","cancel","title","Cancel"))
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
                .doOnNext(System.out::println) // print response
                .doOnError(Throwable::printStackTrace)
                .subscribe(); // fire and forget
    }

}
