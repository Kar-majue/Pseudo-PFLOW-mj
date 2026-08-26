package utils;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class Progressbar {

    private long total;
    private long startTime;

    public Progressbar(long total) {
        this.total = total;
        this.startTime = System.currentTimeMillis();
    }

    public void printProgress(long current) {
        long eta = current == 0 ? 0 : 
            (this.total - current) * (System.currentTimeMillis() - this.startTime) / current;
    
        String etaHms = current == 0 ? "N/A" : 
                String.format("%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(eta),
                        TimeUnit.MILLISECONDS.toMinutes(eta) % TimeUnit.HOURS.toMinutes(1),
                        TimeUnit.MILLISECONDS.toSeconds(eta) % TimeUnit.MINUTES.toSeconds(1));
    
        StringBuilder string = new StringBuilder(140);   
        int percent = (int) (current * 100 / this.total);
        string
            .append('\r')
            .append(String.join("", Collections.nCopies(percent == 0 ? 2 : 2 - (int) (Math.log10(percent)), " ")))
            .append(String.format(" %d%% [", percent))
            .append(String.join("", Collections.nCopies(percent, "=")))
            .append('>')
            .append(String.join("", Collections.nCopies(100 - percent, " ")))
            .append(']')
            .append(String.join("", Collections.nCopies(current == 0 ? (int) (Math.log10(this.total)) : (int) (Math.log10(total)) - (int) (Math.log10(current)), " ")))
            .append(String.format(" %d/%d, ETA: %s", current, this.total, etaHms));
    
        System.out.print(string);
    }
}
