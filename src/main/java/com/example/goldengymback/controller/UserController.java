package com.example.goldengymback.controller;

import com.example.goldengymback.model.User;
import com.example.goldengymback.service.InterfaceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = {
    "http://localhost:5173",
    "https://medecine-cancer-front.vercel.app"
})
public class UserController {

    @Autowired
    private InterfaceClient interfaceClient;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody User user) {
        User existingUser = interfaceClient.loginUser(user.getNom(), user.getPassword());
        if (existingUser != null) {
            return ResponseEntity.ok(existingUser);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Nom ou mot de passe incorrect !");
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User user) {
        User created = interfaceClient.saveUser(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}