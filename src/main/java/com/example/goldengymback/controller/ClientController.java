package com.example.goldengymback.controller;
import org.springframework.security.core.Authentication;
import com.example.goldengymback.model.Client;
import com.example.goldengymback.model.User;
import com.example.goldengymback.repository.UserRepository;
import com.example.goldengymback.service.ClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/clients")
@CrossOrigin(origins = {
    "http://localhost:5173",
    "https://medecine-cancer-front.vercel.app"
})
public class ClientController {

    @Autowired
    private ClientService clientService;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/enregistrer")
    public ResponseEntity<Client> ajouterClient(
            @RequestBody Client client,
            Authentication authentication) {

        if (authentication != null) {
            Long medecinId = (Long) authentication.getPrincipal();
            userRepository.findById(medecinId).ifPresent(client::setMedecin);
        }

        Client savedClient = clientService.ajouterClient(client);
        return new ResponseEntity<>(savedClient, HttpStatus.CREATED);
    }

    @GetMapping("/by-medecin")
    public ResponseEntity<?> getMyClients(Authentication authentication) {
        try {
            if (authentication == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Long medecinId = (Long) authentication.getPrincipal();
            List<Client> clients = clientService.getClientsByMedecinId(medecinId);

            List<Map<String, Object>> result = clients.stream().map(c -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", c.getId());
                map.put("nom", c.getNom());
                map.put("prenom", c.getPrenom());
                map.put("dateNaissance", c.getDateNaissance());
                map.put("telephone", c.getTelephone());
                map.put("renseignementsCliniques", c.getRenseignementsCliniques());
                map.put("emailPatient", c.getEmailPatient());
                map.put("emailMedecin", c.getEmailMedecin());
                return map;
            }).collect(java.util.stream.Collectors.toList());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("ERREUR: " + e.getMessage());
        }
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteClient(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            clientService.deleteClient(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    @DeleteMapping("/delete-all-mine")
    public ResponseEntity<String> deleteAllMyClients(Authentication authentication) {
        try {
            Long medecinId = (Long) authentication.getPrincipal();
            List<Client> clients = clientService.getClientsByMedecinId(medecinId);
            for (Client c : clients) {
                clientService.deleteClient(c.getId());
            }
            return ResponseEntity.ok("Supprimé : " + clients.size() + " clients");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erreur: " + e.getMessage());
        }
    }
}