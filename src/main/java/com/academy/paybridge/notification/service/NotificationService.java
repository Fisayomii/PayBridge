package com.academy.paybridge.notification.service;

import com.academy.paybridge.notification.api.AccountCreatedEvent;
import com.academy.paybridge.notification.api.TransferEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger log = LogManager.getLogger(NotificationService.class);

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public NotificationService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username:noreply@paybridge.com}") String fromEmail
    ) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    public void sendTransferAlerts(TransferEvent event) {
        String debitBody = String.format(
                "PayBridge Transaction Alert [DEBIT]\n\n" +
                        "Wallet: %s\n" +
                        "Amount: %s %s DR\n" +
                        "Beneficiary: %s (%s)\n" +
                        "Description: %s\n" +
                        "Reference: %s\n" +
                        "Status: %s\n" +
                        "Available Balance: %s %s\n",
                event.sourceWalletNumber(), event.currency(), event.amount(),
                event.beneficiaryName(), event.destinationWalletNumber(),
                event.narration(), event.reference(), event.status(),
                event.currency(), event.senderBalanceAfter()
        );

        String creditBody = String.format(
                "PayBridge Transaction Alert [CREDIT]\n\n" +
                        "Hello %s,\n\n" +
                        "Your PayBridge wallet has just been credited!\n\n" +
                        "Wallet: %s\n" +
                        "Amount: %s %s CR\n" +
                        "Sender Wallet: %s\n" +
                        "Description: %s\n" +
                        "Reference: %s\n" +
                        "Status: %s\n" +
                        "Available Balance: %s %s\n",
                event.beneficiaryName(), event.destinationWalletNumber(),
                event.currency(), event.amount(), event.sourceWalletNumber(),
                event.narration(), event.reference(), event.status(),
                event.currency(), event.receiverBalanceAfter()
        );

        log.info("\n==================== [PAYBRIDGE ALERT - DEBIT] ====================\n{}\n===================================================================", debitBody);
        log.info("\n==================== [PAYBRIDGE ALERT - CREDIT] ===================\n{}\n===================================================================", creditBody);

        // Send live email to Sender if they have a real email address
        sendRealEmailIfConfigured(
                event.senderEmail(),
                "PayBridge Debit Alert: " + event.currency() + " " + event.amount() + " [" + event.reference() + "]",
                debitBody
        );

        // Send live email to Receiver (Your Classmate!) if they have a real email address
        sendRealEmailIfConfigured(
                event.receiverEmail(),
                "PayBridge Credit Alert: " + event.currency() + " " + event.amount() + " [" + event.reference() + "]",
                creditBody
        );
    }

    private void sendRealEmailIfConfigured(String toEmail, String subject, String body) {
        if (toEmail == null || toEmail.isBlank() || toEmail.endsWith(".local")) {
            return;
        }
        if (fromEmail == null || fromEmail.contains("your_email@gmail.com")) {
            log.info("[EMAIL SIMULATION] Would have emailed {} (configure MAIL_USERNAME & MAIL_PASSWORD for live delivery)", toEmail);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("[LIVE EMAIL DELIVERED] Sent real transaction alert to {}", toEmail);
        } catch (Exception ex) {
            log.warn("Could not send live email to {}: {}", toEmail, ex.getMessage());
        }
    }

    public void sendWelcomeEmail(AccountCreatedEvent event) {
        String body = String.format(
                "Welcome to PayBridge, %s!\n\n" +
                        "Your NGN Wallet has been successfully created.\n" +
                        "Wallet Number: %s\n\n" +
                        "You can now receive instant transfers.",
                event.ownerName(), event.walletNumber()
        );
        log.info("\n==================== [PAYBRIDGE ALERT - WELCOME] ====================\n{}\n=====================================================================", body);
        sendRealEmailIfConfigured(event.email(), "Welcome to PayBridge!", body);
    }
}