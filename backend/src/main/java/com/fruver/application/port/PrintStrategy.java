package com.fruver.application.port;

import com.fruver.application.service.TicketData;

public interface PrintStrategy {
    void print(TicketData ticket, String location) throws Exception;
    String getStrategyName();
}

