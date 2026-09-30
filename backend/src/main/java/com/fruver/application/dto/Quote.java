package com.fruver.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record Quote(List<QuoteLine> lines, BigDecimal total, boolean complete, String priceType) {}
