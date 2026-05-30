package com.example.goldengymback.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/api/mail")
@CrossOrigin(origins = {
    "http://localhost:5173",
    "https://medecine-cancer-front.vercel.app"
})
public class EmailController {

    @Value("${sendgrid.api.key}")
    private String sendgridApiKey;

    private static final String SENDGRID_URL = "https://api.sendgrid.com/v3/mail/send";
    private static final String FROM_EMAIL   = "selmasouedsd@gmail.com";
    private static final String FROM_NAME    = "Breast AI Report";

    @PostMapping("/send-report")
    public ResponseEntity<String> sendReport(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> recipients = (List<String>) body.get("recipients");
            if (recipients == null || recipients.isEmpty())
                return ResponseEntity.badRequest().body("Aucun destinataire renseigné.");

            String patientName          = (String) body.getOrDefault("patientName", "—");
            String doctorName           = (String) body.getOrDefault("doctorName", "Médecin Radiologue");
            String date                 = (String) body.getOrDefault("date", "—");
            String acrDroit             = (String) body.getOrDefault("acrDroit", "");
            String acrGauche            = (String) body.getOrDefault("acrGauche", "");
            String recommendationDroit  = (String) body.getOrDefault("recommendationDroit", "");
            String recommendationGauche = (String) body.getOrDefault("recommendationGauche", "");
            String acrGlobal            = (String) body.getOrDefault("acrGlobal", "");
            String conduiteGlobale      = (String) body.getOrDefault("conduiteGlobale", "");
            String pdfBase64            = (String) body.get("pdfBase64");

            // ── Corps HTML ─────────────────────────────────────────────────
            StringBuilder html = new StringBuilder();
            html.append("<div style='font-family:Arial,sans-serif;max-width:600px;margin:0 auto;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden'>")
                .append("<div style='background:#1B2B6B;padding:20px 24px'>")
                .append("<h2 style='color:white;margin:0;font-size:18px'>Breast AI Report</h2>")
                .append("<p style='color:rgba(255,255,255,0.7);margin:4px 0 0;font-size:13px'>Hôpital Régional Ksar Hellal — Service d'Imagerie Médicale</p>")
                .append("</div>")
                .append("<div style='padding:24px'>")
                .append("<p style='color:#334155;font-size:14px'>Bonjour,</p>")
                .append("<p style='color:#334155;font-size:14px'>Veuillez trouver ci-joint le compte rendu d'examen écho-mammographique :</p>")
                .append("<table style='width:100%;border-collapse:collapse;margin:16px 0'>")
                .append("<tr><td style='padding:8px 12px;background:#F8FAFC;font-weight:600;color:#1B2B6B;font-size:13px;border:1px solid #e2e8f0'>Patiente</td>")
                .append("<td style='padding:8px 12px;font-size:13px;border:1px solid #e2e8f0'>").append(patientName).append("</td></tr>")
                .append("<tr><td style='padding:8px 12px;background:#F8FAFC;font-weight:600;color:#1B2B6B;font-size:13px;border:1px solid #e2e8f0'>Date</td>")
                .append("<td style='padding:8px 12px;font-size:13px;border:1px solid #e2e8f0'>").append(date).append("</td></tr>");

            if (!acrDroit.isEmpty())
                html.append("<tr><td style='padding:8px 12px;background:#F8FAFC;font-weight:600;color:#1B2B6B;font-size:13px;border:1px solid #e2e8f0'>Sein droit</td>")
                    .append("<td style='padding:8px 12px;font-size:13px;border:1px solid #e2e8f0'>ACR ").append(acrDroit)
                    .append(!recommendationDroit.isEmpty() ? " — " + recommendationDroit : "").append("</td></tr>");

            if (!acrGauche.isEmpty())
                html.append("<tr><td style='padding:8px 12px;background:#F8FAFC;font-weight:600;color:#1B2B6B;font-size:13px;border:1px solid #e2e8f0'>Sein gauche</td>")
                    .append("<td style='padding:8px 12px;font-size:13px;border:1px solid #e2e8f0'>ACR ").append(acrGauche)
                    .append(!recommendationGauche.isEmpty() ? " — " + recommendationGauche : "").append("</td></tr>");

            if (acrDroit.isEmpty() && acrGauche.isEmpty() && !acrGlobal.isEmpty())
                html.append("<tr><td style='padding:8px 12px;background:#F8FAFC;font-weight:600;color:#1B2B6B;font-size:13px;border:1px solid #e2e8f0'>Score ACR</td>")
                    .append("<td style='padding:8px 12px;font-size:13px;border:1px solid #e2e8f0'>ACR ").append(acrGlobal)
                    .append(!conduiteGlobale.isEmpty() ? " — " + conduiteGlobale : "").append("</td></tr>");

            html.append("</table>")
                .append("<p style='color:#64748b;font-size:12px;margin-top:16px'>Le compte rendu complet est joint en pièce jointe (PDF).</p>")
                .append("<hr style='border:none;border-top:1px solid #e2e8f0;margin:20px 0'>")
                .append("<p style='color:#334155;font-size:13px;margin:0'><strong>").append(doctorName).append("</strong></p>")
                .append("<p style='color:#64748b;font-size:12px;margin:4px 0 0'>Médecin Radiologue — Hôpital Régional Ksar Hellal</p>")
                .append("</div></div>");

            // ── Construction payload SendGrid ──────────────────────────────
            List<Map<String, Object>> toList = new ArrayList<>();
            for (String email : recipients) {
                if (email == null || email.isBlank()) continue;
                toList.add(Map.of("email", email));
            }

            Map<String, Object> from = Map.of("email", FROM_EMAIL, "name", FROM_NAME);
            Map<String, Object> personalization = new HashMap<>();
            personalization.put("to", toList);
            personalization.put("subject", "Compte rendu écho-mammographique — " + patientName);

            Map<String, Object> content = Map.of("type", "text/html", "value", html.toString());

            Map<String, Object> payload = new HashMap<>();
            payload.put("personalizations", List.of(personalization));
            payload.put("from", from);
            payload.put("content", List.of(content));

            // Pièce jointe PDF
            if (pdfBase64 != null && !pdfBase64.isBlank()) {
                Map<String, Object> attachment = new HashMap<>();
                attachment.put("content", pdfBase64);
                attachment.put("type", "application/pdf");
                attachment.put("filename", "rapport_" + patientName.replaceAll("\\s+", "_") + ".pdf");
                attachment.put("disposition", "attachment");
                payload.put("attachments", List.of(attachment));
            }

            // ── Appel API SendGrid ─────────────────────────────────────────
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(sendgridApiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                SENDGRID_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok("Rapport envoyé avec succès.");
            } else {
                return ResponseEntity.status(response.getStatusCode())
                    .body("Erreur SendGrid : " + response.getBody());
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Erreur : " + e.getMessage());
        }
    }
}