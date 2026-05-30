package com.example.goldengymback.controller;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mail")
@CrossOrigin(origins = {
    "http://localhost:5173",
    "https://medecine-cancer-front.vercel.app"
})
public class EmailController {

    @Autowired
    private JavaMailSender mailSender;

    /**
     * Endpoint : POST /api/mail/send-report
     * Body JSON :
     * {
     *   "recipients": ["patient@email.com", "medecin@email.com"],
     *   "patientName": "Nom Prénom",
     *   "doctorName": "Dr. Nom",
     *   "date": "30/05/2026",
     *   "acrDroit": "4C",
     *   "acrGauche": "3",
     *   "recommendationDroit": "Biopsie",
     *   "recommendationGauche": "Surveillance — mammographie à 6 mois",
     *   "pdfBase64": "JVBERi0xLjQ..."
     * }
     */
    @PostMapping("/send-report")
    public ResponseEntity<String> sendReport(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> recipients = (List<String>) body.get("recipients");
            if (recipients == null || recipients.isEmpty()) {
                return ResponseEntity.badRequest().body("Aucun destinataire renseigné.");
            }

            String patientName         = (String) body.getOrDefault("patientName", "—");
            String doctorName          = (String) body.getOrDefault("doctorName", "Médecin Radiologue");
            String date                = (String) body.getOrDefault("date", "—");
            String acrDroit            = (String) body.getOrDefault("acrDroit", "");
            String acrGauche           = (String) body.getOrDefault("acrGauche", "");
            String recommendationDroit = (String) body.getOrDefault("recommendationDroit", "");
            String recommendationGauche= (String) body.getOrDefault("recommendationGauche", "");
            String acrGlobal           = (String) body.getOrDefault("acrGlobal", "");
            String conduiteGlobale     = (String) body.getOrDefault("conduiteGlobale", "");
            String pdfBase64           = (String) body.get("pdfBase64");

            // Corps du mail HTML
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

            if (!acrDroit.isEmpty()) {
                html.append("<tr><td style='padding:8px 12px;background:#F8FAFC;font-weight:600;color:#1B2B6B;font-size:13px;border:1px solid #e2e8f0'>Sein droit</td>")
                    .append("<td style='padding:8px 12px;font-size:13px;border:1px solid #e2e8f0'>ACR ").append(acrDroit)
                    .append(!recommendationDroit.isEmpty() ? " — " + recommendationDroit : "")
                    .append("</td></tr>");
            }
            if (!acrGauche.isEmpty()) {
                html.append("<tr><td style='padding:8px 12px;background:#F8FAFC;font-weight:600;color:#1B2B6B;font-size:13px;border:1px solid #e2e8f0'>Sein gauche</td>")
                    .append("<td style='padding:8px 12px;font-size:13px;border:1px solid #e2e8f0'>ACR ").append(acrGauche)
                    .append(!recommendationGauche.isEmpty() ? " — " + recommendationGauche : "")
                    .append("</td></tr>");
            }
            if (acrDroit.isEmpty() && acrGauche.isEmpty() && !acrGlobal.isEmpty()) {
                html.append("<tr><td style='padding:8px 12px;background:#F8FAFC;font-weight:600;color:#1B2B6B;font-size:13px;border:1px solid #e2e8f0'>Score ACR</td>")
                    .append("<td style='padding:8px 12px;font-size:13px;border:1px solid #e2e8f0'>ACR ").append(acrGlobal)
                    .append(!conduiteGlobale.isEmpty() ? " — " + conduiteGlobale : "")
                    .append("</td></tr>");
            }

            html.append("</table>")
                .append("<p style='color:#64748b;font-size:12px;margin-top:16px'>Le compte rendu complet est joint en pièce jointe (PDF).</p>")
                .append("<hr style='border:none;border-top:1px solid #e2e8f0;margin:20px 0'>")
                .append("<p style='color:#334155;font-size:13px;margin:0'><strong>").append(doctorName).append("</strong></p>")
                .append("<p style='color:#64748b;font-size:12px;margin:4px 0 0'>Médecin Radiologue — Hôpital Régional Ksar Hellal</p>")
                .append("</div></div>");

            // Préparer et envoyer le mail à chaque destinataire
            for (String recipient : recipients) {
                if (recipient == null || recipient.isBlank()) continue;

                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

                helper.setFrom("selmasouedsd@gmail.com", "Breast AI Report");
                helper.setTo(recipient);
                helper.setSubject("Compte rendu écho-mammographique — " + patientName);
                helper.setText(html.toString(), true);

                // Attacher le PDF si présent
                if (pdfBase64 != null && !pdfBase64.isBlank()) {
                    byte[] pdfBytes = Base64.getDecoder().decode(pdfBase64);
                    helper.addAttachment(
                        "rapport_" + patientName.replaceAll("\\s+", "_") + ".pdf",
                        new org.springframework.core.io.ByteArrayResource(pdfBytes),
                        "application/pdf"
                    );
                }

                mailSender.send(message);
            }

            return ResponseEntity.ok("Rapport envoyé avec succès.");

        } catch (MessagingException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Erreur d'envoi : " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Erreur : " + e.getMessage());
        }
    }
}