package com.academy.paybridge.notification.service;

import com.academy.paybridge.notification.api.AccountCreatedEvent;
import com.academy.paybridge.notification.api.FundingEvent;
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
        boolean refund = "FAILED".equals(event.status()) || "REVERSED".equals(event.status());
        boolean external = "EXTERNAL".equals(event.transferType());

        if (external && "SUCCESS".equals(event.status())) {
            // The sender was already alerted when the funds were debited; nothing new to tell them.
            log.info("[PAYBRIDGE] Payout {} to {} delivered", event.reference(), event.destination());
            return;
        }

        String senderBody = refund
                ? String.format(
                        "PayBridge Transaction Alert [REVERSAL]\n\n" +
                                "Wallet: %s\n" +
                                "Amount: %s %s CR\n" +
                                "Your transfer to %s (%s) could not be completed and has been refunded.\n" +
                                "Description: %s\n" +
                                "Reference: %s\n" +
                                "Available Balance: %s %s\n",
                        event.sourceWalletNumber(), event.currency(), event.amount(),
                        event.beneficiaryName(), event.destination(),
                        event.narration(), event.reference(),
                        event.currency(), event.senderBalanceAfter())
                : String.format(
                        "PayBridge Transaction Alert [DEBIT]\n\n" +
                                "Wallet: %s\n" +
                                "Amount: %s %s DR\n" +
                                "Beneficiary: %s (%s)\n" +
                                "Description: %s\n" +
                                "Reference: %s\n" +
                                "Status: %s\n" +
                                "Available Balance: %s %s\n",
                        event.sourceWalletNumber(), event.currency(), event.amount(),
                        event.beneficiaryName(), event.destination(),
                        event.narration(), event.reference(), event.status(),
                        event.currency(), event.senderBalanceAfter());

        String alertType = refund ? "Reversal" : "Debit";
        log.info("\n==================== [PAYBRIDGE ALERT - {}] ====================\n{}\n===================================================================",
                alertType.toUpperCase(), senderBody);
        sendRealEmailIfConfigured(
                event.senderEmail(),
                "PayBridge " + alertType + " Alert: " + event.currency() + " " + event.amount() + " [" + event.reference() + "]",
                senderBody
        );

        if (external) {
            return; // the beneficiary banks elsewhere; their bank alerts them
        }

        String creditBody = String.format(
                "PayBridge Transaction Alert [CREDIT]\n\n" +
                        "Hello %s,\n\n" +
                        "Your PayBridge wallet has just been credited!\n\n" +
                        "Wallet: %s\n" +
                        "Amount: %s %s CR\n" +
                        "Sender Wallet: %s\n" +
                        "Description: %s\n" +
                        "Reference: %s\n" +
                        "Available Balance: %s %s\n",
                event.beneficiaryName(), event.destination(),
                event.currency(), event.amount(), event.sourceWalletNumber(),
                event.narration(), event.reference(),
                event.currency(), event.receiverBalanceAfter()
        );
        log.info("\n==================== [PAYBRIDGE ALERT - CREDIT] ===================\n{}\n===================================================================", creditBody);
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

    public void sendFundingAlert(FundingEvent event) {
        boolean credit = "CREDIT".equals(event.direction());
        String body = String.format(
                "PayBridge Transaction Alert [%s]%n%n" +
                        "Wallet: %s%n" +
                        "Amount: %s %s %s%n" +
                        "Description: %s%n" +
                        "Reference: %s%n" +
                        "Available Balance: %s %s%n",
                credit ? "CREDIT" : "DEBIT",
                event.walletNumber(), event.currency(), event.amount(), credit ? "CR" : "DR",
                "PAYSTACK_CHECKOUT".equals(event.channel()) ? "Wallet top-up" : "Account adjustment",
                event.reference(), event.currency(), event.balanceAfter());
        log.info("\n==================== [PAYBRIDGE ALERT - FUNDING] ===================\n{}\n===================================================================", body);
        sendRealEmailIfConfigured(event.email(),
                "PayBridge " + (credit ? "Credit" : "Debit") + " Alert: " + event.currency() + " " + event.amount(),
                body);
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
