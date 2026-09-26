public class Email {
    private String sender;
    private String subject;
    private String body;

    public Email(String sender, String subject, String body) {
        this.sender = sender;
        this.subject = subject;
        this.body = body;
    }

    public String getSender() {
        return sender;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }
}