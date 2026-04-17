package com.example.goldengymback.controller;

import com.example.goldengymback.model.Client;
import com.example.goldengymback.model.User;
import com.example.goldengymback.repository.UserRepository;
import com.example.goldengymback.service.ClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<List<Client>> getMyClients(Authentication authentication) {
        Long medecinId = (Long) authentication.getPrincipal();
        List<Client> clients = clientService.getClientsByMedecinId(medecinId);
        return ResponseEntity.ok(clients);
    }
}