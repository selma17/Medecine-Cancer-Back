package com.example.goldengymback.repository;

import com.example.goldengymback.model.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientRepository extends JpaRepository<Client, Long> {
    List<Client> findByMedecinId(Long medecinId);
}