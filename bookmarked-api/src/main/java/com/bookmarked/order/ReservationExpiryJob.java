package com.bookmarked.order;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: release stock held by online orders that weren't paid within 15 minutes. */
@Component
public class ReservationExpiryJob {

    private final PaymentService payments;

    public ReservationExpiryJob(PaymentService payments) {
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void run() {
        payments.expireOverdue();
    }
}
