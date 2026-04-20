package com.example.goldengymback.serviceImpl;

import com.example.goldengymback.model.Client;
import com.example.goldengymback.repository.ClientRepository;
import com.example.goldengymback.service.ClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClientServiceImpl implements ClientService {

    private final ClientRepository clientRepository;

    @Autowired
    public ClientServiceImpl(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @Override
    public Client ajouterClient(Client client) {
        return clientRepository.save(client);
    }

    @Override
    public List<Client> getClientsByMedecinId(Long medecinId) {
        return clientRepository.findByMedecinId(medecinId);
    }

    @Override
    public void deleteClient(Long id) {
        clientRepository.deleteById(id);
    }
}