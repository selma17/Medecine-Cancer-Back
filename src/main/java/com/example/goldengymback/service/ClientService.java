package com.example.goldengymback.service;

import com.example.goldengymback.model.Client;
import java.util.List;

public interface ClientService {
    Client ajouterClient(Client client);
    List<Client> getClientsByMedecinId(Long medecinId);
}