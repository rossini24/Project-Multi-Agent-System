package rag;

public class DocumentChunk {
    private final String sourceDocument;
    private final String heading;
    private final String content;
    private float[] embedding;

    public DocumentChunk(String sourceDocument, String heading, String content) {
        this.sourceDocument = sourceDocument;
        this.heading = heading;
        this.content = content;
    }

    public String getSourceDocument() { return sourceDocument; }
    public String getHeading() { return heading; }
    public String getContent() { return content; }
    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }
}
