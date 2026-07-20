package demo.photoprocess.withoutframework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * The photo-process workflow — the ONE part shared in spirit with the framework project's
 * {@code PhotoProcessMiddleware}. Given the user's photo it asks the vision model <em>"how should I
 * crop the photo to make it most impactful"</em>, crops it with {@link PhotoProcessor}, and returns
 * the assistant reply.
 *
 * <p>Put this class next to the framework's {@code PhotoProcessMiddleware}: they are ~the same size
 * and do the same job. Everything else in this project ({@link PhotoProcessController},
 * {@link ModelClient}, {@link ResponsesJson}) is hosting/protocol plumbing the framework provides
 * for free.</p>
 */
final class PhotoProcessWorkflow {

    private static final Logger LOG = LoggerFactory.getLogger(PhotoProcessWorkflow.class);

    private static final String CROP_SYSTEM =
            "You are a photo editor. You are shown one photograph and its pixel dimensions. "
                    + "Decide how to crop it to make it the most impactful image: strengthen the "
                    + "composition, remove dead space and distractions, and emphasise the subject "
                    + "(rule of thirds, tighter framing). Respond with STRICT JSON only, no prose, "
                    + "no code fences, of the exact form: "
                    + "{\"x\":int,\"y\":int,\"width\":int,\"height\":int,\"reason\":string}. "
                    + "x,y are the top-left corner and width,height the size of the crop, all in "
                    + "pixels within the given dimensions. Keep the crop inside the image.";

    private final ModelClient model;
    private final ObjectMapper mapper;

    PhotoProcessWorkflow(ModelClient model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    /** Runs the crop workflow for the given turn and returns the assistant's reply text. */
    String run(String userText, Attachment photo) {
        if (photo == null) {
            return "Attach a JPEG photo and I will crop it for maximum impact.";
        }
        try {
            byte[] jpeg = photo.bytes();
            PhotoProcessor.Dimensions dims = PhotoProcessor.dimensions(jpeg);

            PhotoProcessor.CropBox box = adviseCrop(photo, dims);
            byte[] cropped = PhotoProcessor.crop(jpeg, box);

            String dataUri = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(cropped);
            return "Cropped **" + photo.name() + "** to make it more impactful ("
                    + box.width() + "\u00d7" + box.height() + " px from a "
                    + dims.width() + "\u00d7" + dims.height() + " px original).\n\n"
                    + "**Why:** " + box.reason() + "\n\n" + dataUri;
        } catch (Exception error) {
            LOG.warn("Photo-process workflow failed", error);
            return "Sorry — I could not crop that photo: " + error.getMessage();
        }
    }

    /** Asks the vision model how to crop; parses the strict-JSON rectangle. */
    private PhotoProcessor.CropBox adviseCrop(Attachment photo, PhotoProcessor.Dimensions dims)
            throws Exception {
        String prompt = "How should I crop this photo to make it most impactful? "
                + "The image is " + dims.width() + "\u00d7" + dims.height() + " pixels. "
                + "Return the crop rectangle in pixels as JSON.";

        String output = model.completeJson(CROP_SYSTEM, prompt, photo);
        JsonNode node = mapper.readTree(ResponsesJson.stripFences(output));

        int x = node.path("x").asInt(0);
        int y = node.path("y").asInt(0);
        int width = node.path("width").asInt(dims.width());
        int height = node.path("height").asInt(dims.height());
        String reason = node.path("reason").asText("").trim();
        return new PhotoProcessor.CropBox(x, y, width, height, reason);
    }
}
