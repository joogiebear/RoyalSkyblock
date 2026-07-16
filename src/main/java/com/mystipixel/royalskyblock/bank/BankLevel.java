package com.mystipixel.royalskyblock.bank;

import java.util.List;

public record BankLevel(
        int level,
        String name,
        double maxBalance,
        double dailyInterestPercent,
        double maxInterest,
        double upgradeMoneyCost,
        List<ItemRequirement> itemRequirements,
        List<InterestTranche> interestTranches
) {
}
